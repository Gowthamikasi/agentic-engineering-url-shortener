package com.example.urlshortener.orchestration.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** One run of a workflow definition. */
public final class WorkflowInstance {

    private final String runId;
    private final String definitionName;
    private final String policyVersion;
    private final Instant createdAt;
    private final Map<String, Object> input;
    private final AtomicLong sequence = new AtomicLong();

    private WorkflowDefinition definition;
    private InstanceState state = InstanceState.CREATED;
    private String terminalOutcome;
    private Instant terminalAt;
    private String suspendReason;

    private final Map<String, NodeRuntime> nodes = new LinkedHashMap<>();
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    private final List<Decision> decisions = new ArrayList<>();
    private final List<String> assumptions = new ArrayList<>();
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private final List<String> replanningLog = new ArrayList<>();

    public WorkflowInstance(String runId, WorkflowDefinition definition, String policyVersion,
                            Map<String, Object> input, Instant createdAt) {
        this.runId = runId;
        this.definition = definition;
        this.definitionName = definition.name();
        this.policyVersion = policyVersion;
        this.input = new LinkedHashMap<>(input);
        this.createdAt = createdAt;
        definition.nodes().forEach(n -> nodes.put(n.id(), new NodeRuntime(n.id())));
    }

    public String runId() {
        return runId;
    }

    public String definitionName() {
        return definitionName;
    }

    public WorkflowDefinition definition() {
        return definition;
    }

    /** Replanning swaps in a new definition version; nodes added by it start out pending. */
    public synchronized void replaceDefinition(WorkflowDefinition newDefinition) {
        this.definition = newDefinition;
        newDefinition.nodes().forEach(n -> nodes.computeIfAbsent(n.id(), NodeRuntime::new));
    }

    public long definitionVersion() {
        return definition.version();
    }

    public String policyVersion() {
        return policyVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant terminalAt() {
        return terminalAt;
    }

    public Map<String, Object> input() {
        return Map.copyOf(input);
    }

    public synchronized InstanceState state() {
        return state;
    }

    public synchronized void setState(InstanceState state) {
        this.state = state;
    }

    public synchronized String terminalOutcome() {
        return terminalOutcome;
    }

    public synchronized void markTerminal(InstanceState terminalState, String outcome, Instant at) {
        this.state = terminalState;
        this.terminalOutcome = outcome;
        this.terminalAt = at;
    }

    public synchronized String suspendReason() {
        return suspendReason;
    }

    public synchronized void setSuspendReason(String suspendReason) {
        this.suspendReason = suspendReason;
    }

    public long nextSequence() {
        return sequence.incrementAndGet();
    }

    public void alignSequence(long observed) {
        sequence.accumulateAndGet(observed, Math::max);
    }

    public NodeRuntime node(String id) {
        return nodes.computeIfAbsent(id, NodeRuntime::new);
    }

    public synchronized List<NodeRuntime> nodes() {
        return List.copyOf(nodes.values());
    }

    public synchronized void putArtifact(Artifact artifact) {
        artifacts.put(artifact.artifactId(), artifact);
    }

    public synchronized Optional<Artifact> artifact(String artifactId) {
        return Optional.ofNullable(artifacts.get(artifactId));
    }

    public synchronized List<Artifact> artifacts() {
        return List.copyOf(artifacts.values());
    }

    public synchronized void addDecision(Decision decision) {
        decisions.add(decision);
    }

    public synchronized List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    public synchronized Optional<Decision> decisionForGate(String gateId) {
        return decisions.stream().filter(d -> gateId.equals(d.gateId())).reduce((first, second) -> second);
    }

    public synchronized void addAssumption(String assumption) {
        assumptions.add(assumption);
    }

    public synchronized List<String> assumptions() {
        return List.copyOf(assumptions);
    }

    /** Facts contributed by agents, consumed by the policy evaluator. */
    public synchronized void putFacts(Map<String, Object> more) {
        facts.putAll(more);
    }

    public synchronized Map<String, Object> facts() {
        return Map.copyOf(facts);
    }

    public synchronized void recordReplanning(String entry) {
        replanningLog.add(entry);
    }

    public synchronized List<String> replanningLog() {
        return List.copyOf(replanningLog);
    }

    /** True when any node finished in a degraded (fallback) state, which downgrades the outcome. */
    public synchronized boolean hasDegradedNodes() {
        return nodes.values().stream().anyMatch(NodeRuntime::degraded);
    }
}
