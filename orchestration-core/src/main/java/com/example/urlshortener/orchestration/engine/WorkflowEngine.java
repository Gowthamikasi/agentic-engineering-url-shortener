package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Criticality;
import com.example.urlshortener.orchestration.model.Decision;
import com.example.urlshortener.orchestration.model.FailureClass;
import com.example.urlshortener.orchestration.model.InstanceState;
import com.example.urlshortener.orchestration.model.JoinType;
import com.example.urlshortener.orchestration.model.NodeDefinition;
import com.example.urlshortener.orchestration.model.NodeRuntime;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.RecoveryMode;
import com.example.urlshortener.orchestration.model.TransitionEvent;
import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import com.example.urlshortener.orchestration.model.WorkflowInstance;
import com.example.urlshortener.orchestration.port.ApprovalStore;
import com.example.urlshortener.orchestration.port.ArtifactStore;
import com.example.urlshortener.orchestration.port.InstanceStore;
import com.example.urlshortener.orchestration.port.Journal;
import com.example.urlshortener.telemetry.audit.ActorType;
import com.example.urlshortener.telemetry.audit.AuditActions;
import com.example.urlshortener.telemetry.audit.AuditEvent;
import com.example.urlshortener.telemetry.audit.AuditSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Runs a workflow definition as a dependency graph. */
public class WorkflowEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final Map<String, StageAgent> agents;
    private final Journal journal;
    private final ApprovalStore approvals;
    private final InstanceStore instances;
    private final ArtifactStore artifactStore;
    private final RunRehydrator rehydrator;
    private final ObjectMapper json = new ObjectMapper();
    private final AuditSink audit;
    private final BranchEvaluator branches = new BranchEvaluator();
    private final Clock clock;
    private final EngineSettings settings;
    private final Semaphore parallelism;
    private final ExecutorService executor;

    private final Map<String, WorkflowInstance> live = new ConcurrentHashMap<>();
    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, Instant> gateArmedAt = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> runLoops = new ConcurrentHashMap<>();
    private final AtomicLong runCounter = new AtomicLong();

    /** How many runs to keep in memory. */
    private static final int MAX_LIVE_RUNS = 200;

    public WorkflowEngine(Collection<StageAgent> agents, Collection<WorkflowDefinition> knownDefinitions,
                          Journal journal, ApprovalStore approvals, InstanceStore instances,
                          ArtifactStore artifactStore, AuditSink audit, Clock clock,
                          EngineSettings settings) {
        this.agents = new LinkedHashMap<>();
        agents.forEach(a -> this.agents.put(a.agentType(), a));
        // Registered up front, so a run started before a restart can still be rebuilt: without the
        // definition there is no graph to hang the journal's node states on.
        knownDefinitions.forEach(d -> this.definitions.put(d.name(), d));
        this.journal = journal;
        this.approvals = approvals;
        this.instances = instances;
        this.artifactStore = artifactStore;
        this.rehydrator = new RunRehydrator(instances, journal, artifactStore, approvals,
                name -> Optional.ofNullable(definitions.get(name)), this.json);
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
        this.parallelism = new Semaphore(settings.maxParallelism());
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("orchestrator-", 0).factory());
    }

    // ---------------------------------------------------------------- public API

    /** Validates the definition, creates the run and starts it on a background thread. */
    public WorkflowInstance start(WorkflowDefinition definition, Map<String, Object> input, String policyVersion) {
        DagValidator.validate(definition);

        definitions.put(definition.name(), definition);
        String runId = "run_" + Long.toHexString(clock.millis()) + "_" + runCounter.incrementAndGet();
        WorkflowInstance instance = new WorkflowInstance(runId, definition, policyVersion, input, clock.instant());
        live.put(runId, instance);

        record(instance, null, null, null, AuditActions.WORKFLOW_CREATED, ActorType.HUMAN,
                actorOf(input), "Created", "Workflow run created from definition " + definition.name(), null);

        instance.setState(InstanceState.RUNNING);
        persistHeader(instance);
        schedule(instance);
        return instance;
    }

    /** Looks in memory first, then rebuilds the run from the database. */
    public Optional<WorkflowInstance> find(String runId) {
        WorkflowInstance inMemory = live.get(runId);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }
        return rehydrator.rehydrate(runId);
    }

    /** Runs this process is tracking. Use {@link #headers()} for every run ever recorded. */
    public List<WorkflowInstance> all() {
        return List.copyOf(live.values());
    }

    /** Every run in the database, including ones from before a restart. */
    public List<InstanceStore.InstanceRecord> headers() {
        return instances.findAll();
    }

    public Map<String, StageAgent> registeredAgents() {
        return Map.copyOf(agents);
    }

    /** Records a human decision and moves the gate on. */
    public Decision decide(String runId, String gateId, String decisionValue, String actor,
                           String rationale, String conditions) {

        WorkflowInstance instance = require(runId);
        NodeDefinition gateNode = gateNodeFor(instance, gateId);
        NodeRuntime runtime = instance.node(gateNode.id());

        if (runtime.state() != NodeState.AWAITING_APPROVAL) {
            throw new IllegalStateException(
                    "Gate '" + gateId + "' is " + runtime.state() + ", not awaiting a decision.");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("A decision must name its actor.");
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("A decision must carry a rationale.");
        }

        Decision decision = new Decision("dec_" + Long.toHexString(clock.millis()) + "_" + gateNode.id(),
                gateId, "GateDecision", actor, decisionValue.toUpperCase(java.util.Locale.ROOT), rationale,
                conditions, upstreamArtifactIdsOf(instance, gateNode), clock.instant());

        approvals.save(runId, decision);
        instance.addDecision(decision);
        gateArmedAt.remove(gateKey(runId, gateNode.id()));

        record(instance, gateNode.id(), NodeState.AWAITING_APPROVAL, NodeState.AWAITING_APPROVAL,
                AuditActions.APPROVAL_DECIDED, ActorType.HUMAN, actor, decision.decision(), rationale, null);

        switch (decision.decision()) {
            case "REJECT", "NOT_READY" -> {
                transition(instance, gateNode.id(), NodeState.REJECTED, AuditActions.NODE_FAILED,
                        ActorType.HUMAN, actor, "Rejected", rationale);
                terminate(instance, InstanceState.REJECTED, "Rejected at gate " + gateId);
            }
            case "SAFE_STOP" -> {
                transition(instance, gateNode.id(), NodeState.SAFE_STOPPED, AuditActions.SAFE_STOP,
                        ActorType.HUMAN, actor, "SafeStopped", rationale);
                terminate(instance, InstanceState.SAFE_STOPPED, "Safe-stopped at gate " + gateId);
            }
            default -> {
                transition(instance, gateNode.id(), NodeState.APPROVED, AuditActions.APPROVAL_APPLIED,
                        ActorType.HUMAN, actor, decision.decision(), rationale);
                transition(instance, gateNode.id(), NodeState.SUCCEEDED, AuditActions.NODE_SUCCEEDED,
                        ActorType.ENGINE, "engine", decision.decision(), "Gate approved");
                instance.putFacts(Map.of("gate." + gateId + ".decision", decision.decision()));

                if (supersedesUpstream(gateNode)) {
                    replan(instance, gateNode.supersedes(), "Decision at gate " + gateId + ": " + rationale);
                }
                instance.setState(InstanceState.RUNNING);
                schedule(instance);
            }
        }
        persistHeader(instance);
        return decision;
    }

    /** Restarts a suspended or safe-stopped run. */
    public void resume(String runId, String actor, String reason) {
        WorkflowInstance instance = require(runId);
        if (instance.state() == InstanceState.REJECTED) {
            throw new IllegalStateException("A rejected run cannot be resumed.");
        }
        // Blocked nodes go back to pending too, or the resume would do nothing.
        instance.nodes().stream()
                .filter(n -> n.state() == NodeState.SAFE_STOPPED || n.state() == NodeState.BLOCKED)
                .forEach(n -> transition(instance, n.id(), NodeState.PENDING, AuditActions.WORKFLOW_RESUMED,
                        ActorType.HUMAN, actor, "Resumed", reason));

        record(instance, null, null, null, AuditActions.WORKFLOW_RESUMED, ActorType.HUMAN, actor,
                "Resumed", reason, null);
        instance.setState(InstanceState.RUNNING);
        persistHeader(instance);
        schedule(instance);
    }

    /** Stops dispatching new work. */
    public void safeStop(String runId, String actor, String reason) {
        WorkflowInstance instance = require(runId);
        instance.nodes().stream()
                .filter(n -> n.state() == NodeState.AWAITING_APPROVAL || n.state() == NodeState.PENDING)
                .forEach(n -> transition(instance, n.id(), NodeState.SAFE_STOPPED, AuditActions.SAFE_STOP,
                        ActorType.HUMAN, actor, "SafeStopped", reason));
        terminate(instance, InstanceState.SAFE_STOPPED, reason);
        persistHeader(instance);
    }

    /** Safe-stops any gate that has waited longer than the approval timeout. */
    public int expireApprovals() {
        int expired = 0;
        Instant now = clock.instant();

        for (Map.Entry<String, Instant> entry : Map.copyOf(gateArmedAt).entrySet()) {
            if (Duration.between(entry.getValue(), now).compareTo(settings.approvalTimeout()) < 0) {
                continue;
            }
            String[] parts = entry.getKey().split("\u0000", 2);
            WorkflowInstance instance = live.get(parts[0]);
            if (instance == null) {
                gateArmedAt.remove(entry.getKey());
                continue;
            }
            NodeRuntime runtime = instance.node(parts[1]);
            if (runtime.state() != NodeState.AWAITING_APPROVAL) {
                gateArmedAt.remove(entry.getKey());
                continue;
            }
            transition(instance, parts[1], NodeState.SAFE_STOPPED, AuditActions.SAFE_STOP, ActorType.ENGINE,
                    "engine", "SafeStopped", "ApprovalTimeout after " + settings.approvalTimeout());
            gateArmedAt.remove(entry.getKey());
            terminate(instance, InstanceState.SAFE_STOPPED, "ApprovalTimeout at gate " + parts[1]);
            persistHeader(instance);
            expired++;
        }
        return expired;
    }

    /** Blocks until the run is finished or parked at a gate. */
    public boolean awaitQuiescence(String runId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            CompletableFuture<Void> loop = runLoops.get(runId);
            if (loop == null || loop.isDone()) {
                WorkflowInstance instance = live.get(runId);
                if (instance != null && (instance.state().isTerminal() || instance.state().isResumable())) {
                    return true;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- scheduling

    private void schedule(WorkflowInstance instance) {
        runLoops.compute(instance.runId(), (id, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            return CompletableFuture.runAsync(() -> runLoop(instance), executor);
        });
    }

    /** The scheduler loop: work out what can run, dispatch it, wait for the whole wave, repeat. */
    private void runLoop(WorkflowInstance instance) {
        MDC.put("runId", instance.runId());
        try {
            while (instance.state() == InstanceState.RUNNING) {
                List<NodeDefinition> ready = computeReadySet(instance);

                if (ready.isEmpty()) {
                    if (parkOrFinish(instance)) {
                        return;
                    }
                    continue;
                }

                List<NodeDefinition> executable = new ArrayList<>();
                for (NodeDefinition node : ready) {
                    if (node.requiresApproval()) {
                        armGate(instance, node);
                    } else {
                        executable.add(node);
                    }
                }
                if (executable.isEmpty()) {
                    if (parkOrFinish(instance)) {
                        return;
                    }
                    continue;
                }
                dispatchWave(instance, executable);
            }
        } catch (RuntimeException e) {
            log.error("Engine failure on run {}", instance.runId(), e);
            record(instance, null, null, null, AuditActions.SAFE_STOP, ActorType.ENGINE, "engine",
                    "SafeStopped", "Unhandled engine error: " + e, null);
            terminate(instance, InstanceState.SAFE_STOPPED, "Unhandled engine error: " + e.getMessage());
            persistHeader(instance);
        } finally {
            MDC.remove("runId");
        }
    }

    /** @return true when the loop should stop, because the run is parked at a gate or finished */
    private boolean parkOrFinish(WorkflowInstance instance) {
        boolean awaitingApproval = instance.nodes().stream()
                .anyMatch(n -> n.state() == NodeState.AWAITING_APPROVAL);

        if (awaitingApproval) {
            park(instance);
            return true;
        }
        boolean stillActive = instance.nodes().stream().anyMatch(n -> n.state().isActive());
        if (!stillActive) {
            finalizeRun(instance);
            return true;
        }
        return false;
    }

    private void dispatchWave(WorkflowInstance instance, List<NodeDefinition> wave) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (NodeDefinition node : wave) {
            transition(instance, node.id(), NodeState.READY, AuditActions.NODE_READY,
                    ActorType.ENGINE, "engine", "Ready", "Entry gate satisfied");
            futures.add(CompletableFuture.runAsync(() -> executeNodeWithRetries(instance, node), executor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    /** Finds the nodes that can start now. */
    private synchronized List<NodeDefinition> computeReadySet(WorkflowInstance instance) {
        List<NodeDefinition> ready = new ArrayList<>();

        for (NodeDefinition node : instance.definition().nodes()) {
            NodeRuntime runtime = instance.node(node.id());
            if (runtime.state() != NodeState.PENDING) {
                continue;
            }

            List<String> unmet = unmetDependencies(instance, node);
            if (!unmet.isEmpty()) {
                if (isBlockedUpstream(instance, node)) {
                    transition(instance, node.id(), NodeState.BLOCKED, AuditActions.NODE_BLOCKED,
                            ActorType.ENGINE, "engine", "Blocked",
                            "A blocking dependency did not succeed: " + unmet);
                }
                continue;
            }

            boolean conditionHolds = branches.evaluate(node.branchCondition(), instance.input(), instance.facts());
            if (!conditionHolds) {
                transition(instance, node.id(), NodeState.SKIPPED, AuditActions.NODE_SKIPPED,
                        ActorType.ENGINE, "engine", "Skipped", branches.describe(node.branchCondition(), false));
                continue;
            }
            ready.add(node);
        }
        return ready;
    }

    private List<String> unmetDependencies(WorkflowInstance instance, NodeDefinition node) {
        if (node.dependsOn().isEmpty()) {
            return List.of();
        }
        List<String> unmet = node.dependsOn().stream()
                .filter(dep -> !instance.node(dep).state().satisfiesDependency())
                .toList();

        if (node.joinType() == JoinType.ANY) {
            return unmet.size() == node.dependsOn().size() ? unmet : List.of();
        }
        return unmet;
    }

    /** True when a dependency finished without succeeding and was marked blocking. */
    private boolean isBlockedUpstream(WorkflowInstance instance, NodeDefinition node) {
        for (String dependencyId : node.dependsOn()) {
            NodeState state = instance.node(dependencyId).state();
            boolean settledUnsuccessfully = state.isSettled() && !state.satisfiesDependency();
            if (!settledUnsuccessfully) {
                continue;
            }
            Criticality criticality = instance.definition().node(dependencyId)
                    .map(NodeDefinition::criticality).orElse(Criticality.BLOCKING);
            if (criticality == Criticality.BLOCKING) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- node execution

    /** Runs one node, retrying while the failure is transient and the budget allows. */
    private void executeNodeWithRetries(WorkflowInstance instance, NodeDefinition node) {
        while (true) {
            int attempt = instance.node(node.id()).nextAttempt();

            transition(instance, node.id(), NodeState.RUNNING, AuditActions.NODE_STARTED,
                    ActorType.AGENT, node.agentType(), "Running", "Attempt " + attempt);
            instance.node(node.id()).markStarted(clock.instant());

            Attempt outcome = runAttempt(instance, node, attempt);
            boolean resultRecorded = false;

            if (outcome.succeeded()) {
                applyResult(instance, node, outcome.result());
                resultRecorded = true;

                // Exit gate: the agent says it succeeded, so check it produced what it declared.
                List<String> missing = missingExitArtifacts(instance, node);
                if (missing.isEmpty()) {
                    instance.node(node.id()).markEnded(clock.instant());
                    transition(instance, node.id(), NodeState.SUCCEEDED, AuditActions.NODE_SUCCEEDED,
                            ActorType.AGENT, node.agentType(), outcome.result().message(), null);
                    return;
                }

                String reason = "Exit gate not satisfied: " + node.id() + " reported success but did not "
                        + "produce " + missing;
                record(instance, node.id(), NodeState.RUNNING, NodeState.RUNNING,
                        AuditActions.EXIT_GATE_FAILED, ActorType.ENGINE, "engine", "ExitGateFailed",
                        reason, null);
                // A missing output will not appear on a retry, so do not spend the budget.
                outcome = Attempt.failed(FailureClass.PERMANENT, false, reason, outcome.result());
            }

            // Keep whatever the failed attempt produced, so there is something to look at. Skipped
            // when the exit gate rejected an otherwise successful attempt, which already recorded it.
            if (outcome.result() != null && !resultRecorded) {
                applyResult(instance, node, outcome.result());
            }
            instance.node(node.id()).recordFailure(outcome.failureClass(), outcome.message());

            NodeState failureState = outcome.timedOut() ? NodeState.TIMED_OUT : NodeState.FAILED;
            String failureAction = outcome.timedOut() ? AuditActions.NODE_TIMED_OUT : AuditActions.NODE_FAILED;
            transition(instance, node.id(), failureState, failureAction, ActorType.AGENT, node.agentType(),
                    outcome.failureClass().name(), outcome.message());

            boolean retryable = outcome.failureClass() == FailureClass.TRANSIENT && attempt < node.maxAttempts();
            if (!retryable) {
                instance.node(node.id()).markEnded(clock.instant());
                applyRecovery(instance, node, outcome);
                return;
            }

            long backoff = backoffMillis(node, attempt);
            transition(instance, node.id(), NodeState.RETRYING, AuditActions.NODE_RETRY_SCHEDULED,
                    ActorType.ENGINE, "engine", "attempt " + (attempt + 1) + " in " + backoff + "ms",
                    outcome.message());
            sleep(backoff);
            transition(instance, node.id(), NodeState.READY, AuditActions.NODE_READY,
                    ActorType.ENGINE, "engine", "Ready", "Backoff elapsed");
        }
    }

    /** Runs a single attempt, bounded by the node's timeout. */
    private Attempt runAttempt(WorkflowInstance instance, NodeDefinition node, int attempt) {
        StageAgent agent = agents.get(node.agentType());
        if (agent == null) {
            return Attempt.failed(FailureClass.PERMANENT, false,
                    "No agent registered for type '" + node.agentType() + "'", null);
        }

        StageContext context = contextFor(instance, node, attempt);
        boolean acquired = false;
        try {
            parallelism.acquire();
            acquired = true;
            CompletableFuture<StageResult> future =
                    CompletableFuture.supplyAsync(() -> invoke(agent, context), executor);
            StageResult result = future.get(node.timeoutMs(), TimeUnit.MILLISECONDS);

            if (result.succeeded()) {
                return Attempt.succeeded(result);
            }
            return Attempt.failed(FailureClassifier.reconcile(result.failureClass(), result.message()),
                    false, result.message(), result);

        } catch (TimeoutException e) {
            return Attempt.failed(FailureClass.TRANSIENT, true,
                    "Attempt exceeded the node timeout of " + node.timeoutMs() + "ms", null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return Attempt.failed(FailureClassifier.classify(cause), false,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Attempt.failed(FailureClass.PERMANENT, false, "Interrupted", null);
        } finally {
            if (acquired) {
                parallelism.release();
            }
        }
    }

    private static StageResult invoke(StageAgent agent, StageContext context) {
        try {
            return agent.execute(context);
        } catch (Exception e) {
            throw new CompletionFailure(e);
        }
    }

    /** Carries a checked agent exception across the CompletableFuture boundary. */
    private static final class CompletionFailure extends RuntimeException {

        CompletionFailure(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Outcome of one attempt. */
    private record Attempt(StageResult result, FailureClass failureClass, boolean timedOut, String message) {

        static Attempt succeeded(StageResult result) {
            return new Attempt(result, null, false, result.message());
        }

        static Attempt failed(FailureClass failureClass, boolean timedOut, String message, StageResult partial) {
            return new Attempt(partial, failureClass, timedOut, message);
        }

        boolean succeeded() {
            return failureClass == null;
        }
    }

    private StageContext contextFor(WorkflowInstance instance, NodeDefinition node, int attempt) {
        List<Artifact> upstream = new ArrayList<>();
        for (String artifactId : upstreamArtifactIdsOf(instance, node)) {
            instance.artifact(artifactId).ifPresent(upstream::add);
        }
        return new StageContext(instance.runId(), node.id(), attempt, instance.definitionVersion(),
                instance.policyVersion(), instance.input(), upstream, instance.decisions(),
                instance.assumptions(), instance.facts());
    }

    /** Stores the artifacts, facts and assumptions an attempt produced. */
    private void applyResult(WorkflowInstance instance, NodeDefinition node, StageResult result) {
        for (StageResult.ArtifactDraft draft : result.artifacts()) {
            int version = instance.artifacts().stream()
                    .filter(a -> a.nodeId().equals(node.id()) && a.type().equals(draft.type()))
                    .mapToInt(Artifact::version).max().orElse(0) + 1;

            String artifactId = node.id() + ":" + draft.type() + ":v" + version;
            Artifact artifact = new Artifact(artifactId, node.id(), draft.type(), version,
                    sha256(draft.contentJson()), draft.contentJson(),
                    draft.inputArtifactIds().isEmpty()
                            ? upstreamArtifactIdsOf(instance, node) : draft.inputArtifactIds(),
                    instance.decisions().stream().map(Decision::decisionId).toList(),
                    clock.instant(), false);

            instance.putArtifact(artifact);
            instance.node(node.id()).addArtifact(artifactId);
            artifactStore.save(instance.runId(), artifact);
        }
        instance.putFacts(result.facts());
        result.assumptions().forEach(instance::addAssumption);
    }

    /** Artifact types the node declared but did not produce. */
    private static List<String> missingExitArtifacts(WorkflowInstance instance, NodeDefinition node) {
        if (!node.hasExitGate()) {
            return List.of();
        }
        Set<String> produced = instance.node(node.id()).artifactIds().stream()
                .map(instance::artifact)
                .filter(Optional::isPresent)
                .map(a -> a.get().type())
                .collect(java.util.stream.Collectors.toSet());

        return node.producesArtifacts().stream().filter(type -> !produced.contains(type)).toList();
    }

    /** Runs the node's declared recovery after a failure that will not be retried. */
    private void applyRecovery(WorkflowInstance instance, NodeDefinition node, Attempt outcome) {
        if (node.fallbackAllowed()) {
            transition(instance, node.id(), NodeState.FALLING_BACK, AuditActions.FALLBACK_APPLIED,
                    ActorType.ENGINE, "engine", "Degraded",
                    "Fallback applied; node result is degraded: " + outcome.message());
            instance.node(node.id()).markDegraded();
            instance.putFacts(Map.of("run.degraded", true));
            transition(instance, node.id(), NodeState.SUCCEEDED, AuditActions.NODE_SUCCEEDED,
                    ActorType.ENGINE, "engine", "Degraded", "Completed via fallback");
            return;
        }

        switch (node.recoveryMode()) {
            case ROLLBACKABLE -> {
                transition(instance, node.id(), NodeState.ROLLING_BACK, AuditActions.ROLLBACK_STARTED,
                        ActorType.ENGINE, "engine", "RollingBack", "Reverting artifacts of " + node.id());
                instance.node(node.id()).revertArtifacts();
                transition(instance, node.id(), NodeState.ROLLED_BACK, AuditActions.ROLLBACK_COMPLETED,
                        ActorType.ENGINE, "engine", "RolledBack", "Artifacts reverted to their prior version");
                transition(instance, node.id(), NodeState.SAFE_STOPPED, AuditActions.SAFE_STOP,
                        ActorType.ENGINE, "engine", "SafeStopped",
                        "Rolled back and awaiting a human decision: " + outcome.message());
            }
            case COMPENSATABLE -> {
                transition(instance, node.id(), NodeState.COMPENSATING, AuditActions.COMPENSATION_STARTED,
                        ActorType.ENGINE, "engine", "Compensating",
                        "Side effect already observed; recording a compensating action");
                transition(instance, node.id(), NodeState.COMPENSATED, AuditActions.COMPENSATION_COMPLETED,
                        ActorType.ENGINE, "engine", "Compensated", "Compensating action recorded");
                transition(instance, node.id(), NodeState.SAFE_STOPPED, AuditActions.SAFE_STOP,
                        ActorType.ENGINE, "engine", "SafeStopped",
                        "Compensated and awaiting a human decision: " + outcome.message());
            }
            case NONE -> transition(instance, node.id(), NodeState.SAFE_STOPPED, AuditActions.SAFE_STOP,
                    ActorType.ENGINE, "engine", "SafeStopped",
                    "No recovery is defined for this node: " + outcome.message());
            default -> throw new IllegalStateException("Unhandled recovery mode " + node.recoveryMode());
        }
    }

    private long backoffMillis(NodeDefinition node, int attempt) {
        double base = node.backoffBaseMs() * Math.pow(2, attempt - 1D);
        double jitter = base * settings.jitterFactor() * (Math.random() * 2 - 1);
        return Math.max(1, Math.round(base + jitter));
    }

    // ---------------------------------------------------------------- gates

    private void armGate(WorkflowInstance instance, NodeDefinition node) {
        transition(instance, node.id(), NodeState.READY, AuditActions.NODE_READY,
                ActorType.ENGINE, "engine", "Ready", "Entry gate satisfied");
        transition(instance, node.id(), NodeState.AWAITING_APPROVAL, AuditActions.APPROVAL_REQUESTED,
                ActorType.ENGINE, "engine", "AwaitingApproval",
                "Human decision required at gate " + node.effectiveGateId());
        gateArmedAt.put(gateKey(instance.runId(), node.id()), clock.instant());
    }

    private void park(WorkflowInstance instance) {
        boolean clarification = instance.nodes().stream()
                .filter(n -> n.state() == NodeState.AWAITING_APPROVAL)
                .anyMatch(n -> instance.definition().node(n.id())
                        .map(d -> d.effectiveGateId().contains("clarify"))
                        .orElse(false));

        InstanceState parked = clarification ? InstanceState.AWAITING_CLARIFICATION : InstanceState.SUSPENDED;
        instance.setState(parked);
        instance.setSuspendReason("Awaiting a human decision");
        record(instance, null, null, null,
                clarification ? AuditActions.WORKFLOW_AWAITING_CLARIFICATION : AuditActions.WORKFLOW_SUSPENDED,
                ActorType.ENGINE, "engine", parked.name(), "Run parked at a human gate", null);
        persistHeader(instance);
    }

    private NodeDefinition gateNodeFor(WorkflowInstance instance, String gateId) {
        return instance.definition().nodes().stream()
                .filter(NodeDefinition::requiresApproval)
                .filter(n -> n.effectiveGateId().equals(gateId) || n.id().equals(gateId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such gate: " + gateId));
    }

    private static String gateKey(String runId, String nodeId) {
        return runId + "\u0000" + nodeId;
    }

    // ---------------------------------------------------------------- replanning

    private static boolean supersedesUpstream(NodeDefinition gateNode) {
        return gateNode.supersedes() != null && !gateNode.supersedes().isBlank();
    }

    /** Invalidates everything downstream of a superseded node and re-runs it under a new definition version. */
    public void replan(WorkflowInstance instance, String supersededNodeId, String reason) {
        long fromVersion = instance.definitionVersion();
        Set<String> closure = downstreamClosure(instance.definition(), supersededNodeId);

        List<String> invalidated = new ArrayList<>();
        for (String nodeId : closure) {
            NodeRuntime runtime = instance.node(nodeId);
            if (runtime.state() == NodeState.PENDING || runtime.state() == NodeState.REJECTED) {
                continue;
            }
            if (NodeStateMachine.isAllowed(runtime.state(), NodeState.INVALIDATED)) {
                transition(instance, nodeId, NodeState.INVALIDATED, AuditActions.INVALIDATED,
                        ActorType.ENGINE, "engine", "Invalidated",
                        "Upstream artifact of '" + supersededNodeId + "' was superseded");
            }
            runtime.resetForReplan();
            if (NodeStateMachine.isAllowed(runtime.state(), NodeState.PENDING)) {
                transition(instance, nodeId, NodeState.PENDING, AuditActions.NODE_READY,
                        ActorType.ENGINE, "engine", "Pending", "Regenerating under the new definition version");
            }
            invalidated.add(nodeId);
        }

        long toVersion = fromVersion + 1;
        instance.replaceDefinition(instance.definition().withVersion(toVersion));
        instance.recordReplanning("v" + fromVersion + " -> v" + toVersion + ": invalidated " + invalidated
                + " because " + reason);

        record(instance, null, null, null, AuditActions.REPLANNED, ActorType.ENGINE, "engine",
                "v" + fromVersion + " -> v" + toVersion, reason,
                "{\"fromVersion\":" + fromVersion + ",\"toVersion\":" + toVersion
                        + ",\"invalidatedNodes\":" + jsonArray(invalidated) + "}");
    }

    /** Nodes that depend on the given node, directly or indirectly. */
    static Set<String> downstreamClosure(WorkflowDefinition definition, String nodeId) {
        Set<String> closure = new LinkedHashSet<>();
        Set<String> frontier = new HashSet<>(Set.of(nodeId));

        while (!frontier.isEmpty()) {
            Set<String> next = new LinkedHashSet<>();
            for (String current : frontier) {
                for (String consumer : definition.consumersOf(current)) {
                    if (closure.add(consumer)) {
                        next.add(consumer);
                    }
                }
            }
            frontier = next;
        }
        return closure;
    }

    // ---------------------------------------------------------------- completion

    /** Works out the terminal state. */
    private void finalizeRun(WorkflowInstance instance) {
        List<NodeRuntime> nodes = instance.nodes();

        boolean anyFailed = nodes.stream()
                .anyMatch(n -> n.state() == NodeState.FAILED || n.state() == NodeState.BLOCKED);
        boolean anySafeStopped = nodes.stream().anyMatch(n -> n.state() == NodeState.SAFE_STOPPED);
        boolean anyRejected = nodes.stream().anyMatch(n -> n.state() == NodeState.REJECTED);

        if (anyRejected) {
            terminate(instance, InstanceState.REJECTED, "A gate was rejected");
        } else if (anySafeStopped) {
            terminate(instance, InstanceState.SAFE_STOPPED, "One or more nodes safe-stopped");
        } else if (anyFailed) {
            terminate(instance, InstanceState.FAILED, "One or more nodes failed without recovery");
        } else if (instance.hasDegradedNodes()) {
            terminate(instance, InstanceState.COMPLETED_WITH_LIMITATIONS,
                    "Completed with one or more degraded nodes");
        } else {
            terminate(instance, InstanceState.COMPLETED, "All nodes settled successfully");
        }
        persistHeader(instance);
    }

    private void terminate(WorkflowInstance instance, InstanceState state, String outcome) {
        if (instance.state().isTerminal()) {
            return;
        }
        instance.markTerminal(state, outcome, clock.instant());
        record(instance, null, null, null, actionFor(state), ActorType.ENGINE, "engine",
                state.name(), outcome, null);
    }

    private static String actionFor(InstanceState state) {
        return switch (state) {
            case COMPLETED -> AuditActions.WORKFLOW_COMPLETED;
            case COMPLETED_WITH_LIMITATIONS -> AuditActions.WORKFLOW_COMPLETED_WITH_LIMITATIONS;
            case REJECTED -> AuditActions.WORKFLOW_REJECTED;
            case SAFE_STOPPED -> AuditActions.SAFE_STOP;
            case FAILED -> AuditActions.WORKFLOW_FAILED;
            default -> AuditActions.WORKFLOW_SUSPENDED;
        };
    }

    // ---------------------------------------------------------------- journal and audit

    /** Checks the transition is legal, journals it, then applies it. */
    private synchronized void transition(WorkflowInstance instance, String nodeId, NodeState to,
                                         String action, ActorType actorType, String actorId,
                                         String result, String reason) {

        NodeRuntime runtime = instance.node(nodeId);
        NodeState from = runtime.state();
        if (from == to && to == NodeState.AWAITING_APPROVAL) {
            return;
        }
        NodeStateMachine.requireAllowed(nodeId, from, to);

        record(instance, nodeId, from, to, action, actorType, actorId, result, reason, null);
        runtime.setState(to);
        if (reason != null) {
            runtime.setReason(reason);
        }
    }

    private void record(WorkflowInstance instance, String nodeId, NodeState from, NodeState to,
                        String action, ActorType actorType, String actorId, String result,
                        String reason, String payloadJson) {

        long seq = instance.nextSequence();
        Instant now = clock.instant();

        journal.append(new TransitionEvent(instance.runId(), seq, nodeId, from, to, action,
                actorType.name(), actorId, result, reason, instance.definitionVersion(),
                instance.policyVersion(), now, payloadJson));

        audit.append(new AuditEvent(instance.runId(), seq, now, actorType, actorId, action,
                nodeId == null ? "workflow" : "node", nodeId == null ? instance.runId() : nodeId,
                result, reason, instance.policyVersion(), instance.definitionVersion(), null, null));
    }

    private void persistHeader(WorkflowInstance instance) {
        instances.save(new InstanceStore.InstanceRecord(instance.runId(), instance.definitionName(),
                instance.definitionVersion(), instance.policyVersion(), instance.state().name(),
                instance.terminalOutcome(), instance.createdAt(), instance.terminalAt(),
                writeJson(instance.input()), writeJson(instance.facts())));
        evictSettledRuns();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Drops finished runs from memory once there are more than {@link #MAX_LIVE_RUNS}; they can be
     * rebuilt from the database on demand.
     */
    private void evictSettledRuns() {
        if (live.size() <= MAX_LIVE_RUNS) {
            return;
        }
        live.entrySet().stream()
                .filter(e -> e.getValue().state().isTerminal())
                .sorted(Comparator.comparing(e -> e.getValue().createdAt()))
                .limit(Math.max(0, live.size() - MAX_LIVE_RUNS))
                .map(Map.Entry::getKey)
                .forEach(runId -> {
                    live.remove(runId);
                    runLoops.remove(runId);
                });
    }

    // ---------------------------------------------------------------- helpers

    private WorkflowInstance require(String runId) {
        WorkflowInstance instance = live.get(runId);
        if (instance == null) {
            throw new IllegalArgumentException("No such run: " + runId);
        }
        return instance;
    }

    /** Artifacts feeding this node. */
    private static List<String> upstreamArtifactIdsOf(WorkflowInstance instance, NodeDefinition node) {
        List<String> ids = new ArrayList<>();
        collectUpstreamArtifacts(instance, node.dependsOn(), new HashSet<>(), ids);
        return ids;
    }

    private static void collectUpstreamArtifacts(WorkflowInstance instance, List<String> dependencyIds,
                                                 Set<String> visited, List<String> collected) {
        for (String dependencyId : dependencyIds) {
            if (!visited.add(dependencyId)) {
                continue;
            }
            List<String> artifacts = instance.node(dependencyId).artifactIds();
            if (!artifacts.isEmpty()) {
                collected.addAll(artifacts);
                continue;
            }
            instance.definition().node(dependencyId).ifPresent(upstream ->
                    collectUpstreamArtifacts(instance, upstream.dependsOn(), visited, collected));
        }
    }

    private static String actorOf(Map<String, Object> input) {
        Object actor = input.get("actor");
        return actor == null ? "unknown" : actor.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String jsonArray(List<String> values) {
        return values.stream()
                .map(v -> "\"" + v.replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
