package com.example.urlshortener.orchestration.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Mutable per-node runtime state. Guarded by the owning {@link WorkflowInstance}'s monitor. */
public final class NodeRuntime {

    private final String id;
    private NodeState state = NodeState.PENDING;
    private int attempts;
    private Instant startedAt;
    private Instant endedAt;
    private Instant nextAttemptAt;
    private String reason;
    private FailureClass lastFailureClass;
    private String lastFailureReason;
    private final List<String> artifactIds = new ArrayList<>();
    private boolean degraded;

    public NodeRuntime(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public NodeState state() {
        return state;
    }

    public void setState(NodeState state) {
        this.state = state;
    }

    public int attempts() {
        return attempts;
    }

    public int nextAttempt() {
        return ++attempts;
    }

    /**
     * Rollback clears what the node produced but keeps the attempt count, so the journal still
     * shows how many times it ran.
     */
    public void revertArtifacts() {
        artifactIds.clear();
        endedAt = null;
        degraded = false;
    }

    /** Replanning re-runs the node against different inputs, so the attempt series starts over. */
    public void resetForReplan() {
        attempts = 0;
        startedAt = null;
        endedAt = null;
        nextAttemptAt = null;
        lastFailureClass = null;
        lastFailureReason = null;
        degraded = false;
        artifactIds.clear();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void markStarted(Instant at) {
        if (startedAt == null) {
            startedAt = at;
        }
    }

    public Instant endedAt() {
        return endedAt;
    }

    public void markEnded(Instant at) {
        this.endedAt = at;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant at) {
        this.nextAttemptAt = at;
    }

    public String reason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public FailureClass lastFailureClass() {
        return lastFailureClass;
    }

    public String lastFailureReason() {
        return lastFailureReason;
    }

    public void recordFailure(FailureClass failureClass, String failureReason) {
        this.lastFailureClass = failureClass;
        this.lastFailureReason = failureReason;
    }

    public List<String> artifactIds() {
        return List.copyOf(artifactIds);
    }

    public void addArtifact(String artifactId) {
        artifactIds.add(artifactId);
    }

    public boolean degraded() {
        return degraded;
    }

    public void markDegraded() {
        this.degraded = true;
    }

    public NodeSnapshot snapshot(List<String> waitingOn) {
        return new NodeSnapshot(id, state, attempts, startedAt, endedAt, nextAttemptAt, reason,
                lastFailureClass, lastFailureReason, waitingOn, artifactIds(), degraded);
    }
}
