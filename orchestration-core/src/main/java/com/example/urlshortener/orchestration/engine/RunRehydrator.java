package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Decision;
import com.example.urlshortener.orchestration.model.InstanceState;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.TransitionEvent;
import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import com.example.urlshortener.orchestration.model.WorkflowInstance;
import com.example.urlshortener.orchestration.port.ApprovalStore;
import com.example.urlshortener.orchestration.port.ArtifactStore;
import com.example.urlshortener.orchestration.port.InstanceStore;
import com.example.urlshortener.orchestration.port.Journal;
import com.example.urlshortener.telemetry.audit.AuditActions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Rebuilds a finished run from the database when it is no longer in memory. */
public final class RunRehydrator {

    private final InstanceStore instances;
    private final Journal journal;
    private final ArtifactStore artifacts;
    private final ApprovalStore approvals;
    private final Function<String, Optional<WorkflowDefinition>> definitions;
    private final ObjectMapper json;

    public RunRehydrator(InstanceStore instances, Journal journal, ArtifactStore artifacts,
                         ApprovalStore approvals, Function<String, Optional<WorkflowDefinition>> definitions,
                         ObjectMapper json) {
        this.instances = instances;
        this.journal = journal;
        this.artifacts = artifacts;
        this.approvals = approvals;
        this.definitions = definitions;
        this.json = json;
    }

    /** Rebuilds the run, or returns empty if it was never recorded or its definition is gone. */
    public Optional<WorkflowInstance> rehydrate(String runId) {
        Optional<InstanceStore.InstanceRecord> header = instances.find(runId);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        InstanceStore.InstanceRecord record = header.get();

        Optional<WorkflowDefinition> definition = definitions.apply(record.definitionName());
        if (definition.isEmpty()) {
            return Optional.empty();
        }

        WorkflowInstance instance = new WorkflowInstance(record.runId(),
                definition.get().withVersion(record.definitionVersion()),
                record.policyVersion(), readMap(record.inputJson()), record.createdAt());

        replayNodeStates(instance, journal.findByRun(runId));
        restoreArtifacts(instance);
        approvals.findByRun(runId).forEach(instance::addDecision);
        instance.putFacts(readMap(record.factsJson()));
        restoreRunState(instance, record);

        return Optional.of(instance);
    }

    public List<InstanceStore.InstanceRecord> headers() {
        return instances.findAll();
    }

    /** The journal is the source of truth, so node state is whatever its last transition says. */
    private void replayNodeStates(WorkflowInstance instance, List<TransitionEvent> events) {
        for (TransitionEvent event : events) {
            instance.alignSequence(event.seq());
            if (event.nodeId() == null) {
                continue;
            }
            var node = instance.node(event.nodeId());

            if (AuditActions.NODE_STARTED.equals(event.action())) {
                node.nextAttempt();
                node.markStarted(event.timestamp());
            }
            if (AuditActions.FALLBACK_APPLIED.equals(event.action())) {
                node.markDegraded();
            }
            if (event.toState() != null) {
                node.setState(event.toState());
                if (event.reason() != null) {
                    node.setReason(event.reason());
                }
                if (event.toState().isSettled()) {
                    node.markEnded(event.timestamp());
                }
            }
            if (AuditActions.NODE_FAILED.equals(event.action())
                    || AuditActions.NODE_TIMED_OUT.equals(event.action())
                    || AuditActions.EXIT_GATE_FAILED.equals(event.action())) {
                node.recordFailure(failureClassOf(event.result()), event.reason());
            }
        }
    }

    private void restoreArtifacts(WorkflowInstance instance) {
        for (Artifact artifact : artifacts.findByRun(instance.runId())) {
            instance.putArtifact(artifact);
            instance.node(artifact.nodeId()).addArtifact(artifact.artifactId());
        }
    }

    private static void restoreRunState(WorkflowInstance instance, InstanceStore.InstanceRecord record) {
        InstanceState state = InstanceState.valueOf(record.state());
        if (state.isTerminal()) {
            instance.markTerminal(state, record.terminalOutcome(), record.terminalAt());
        } else {
            instance.setState(state);
        }
    }

    private static com.example.urlshortener.orchestration.model.FailureClass failureClassOf(String result) {
        try {
            return com.example.urlshortener.orchestration.model.FailureClass.valueOf(result);
        } catch (IllegalArgumentException | NullPointerException e) {
            return com.example.urlshortener.orchestration.model.FailureClass.PERMANENT;
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Decisions are stored separately, so they are readable even without a rebuilt run. */
    public List<Decision> decisionsFor(String runId) {
        return approvals.findByRun(runId);
    }

    /** Node states as the journal last left them, without rebuilding the whole run. */
    public Map<String, NodeState> nodeStatesFor(String runId) {
        return journal.findByRun(runId).stream()
                .filter(e -> e.nodeId() != null && e.toState() != null)
                .collect(java.util.stream.Collectors.toMap(TransitionEvent::nodeId, TransitionEvent::toState,
                        (first, second) -> second));
    }
}
