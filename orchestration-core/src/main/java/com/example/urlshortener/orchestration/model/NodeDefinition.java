package com.example.urlshortener.orchestration.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One node of a workflow definition, loaded from versioned JSON.
 *
 * <p>Retry, timeout and recovery are declared here rather than inside agents, so the engine stays
 * the only thing that retries and the journal's attempt count stays accurate.
 *
 * @param id              node id, unique within the definition
 * @param agentType       which registered agent runs this node
 * @param dependsOn       upstream node ids; part of the entry gate
 * @param joinType        whether all or any dependency must be satisfied
 * @param requiresApproval whether this node is a human gate
 * @param gateId          the id callers post decisions to
 * @param timeoutMs       per-attempt timeout
 * @param maxAttempts     total attempts including the first (1 means no retry)
 * @param backoffBaseMs   base for exponential backoff between attempts
 * @param recoveryMode    what can be undone if this node fails
 * @param criticality     whether failure blocks everything downstream
 * @param branchCondition SpEL over the run context; false skips the node
 * @param fallbackAllowed whether a permanent failure can be absorbed as a degraded success
 * @param destructive     whether the node does something irreversible
 * @param supersedes      for a gate: the upstream node whose artifact this decision replaces,
 *                        which is what turns the approval into a replanning trigger
 * @param producesArtifacts the exit gate: artifact types that must exist before the node counts
 *                        as successful
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeDefinition(
        String id,
        String agentType,
        List<String> dependsOn,
        JoinType joinType,
        boolean requiresApproval,
        String gateId,
        long timeoutMs,
        int maxAttempts,
        long backoffBaseMs,
        RecoveryMode recoveryMode,
        Criticality criticality,
        String branchCondition,
        boolean fallbackAllowed,
        boolean destructive,
        String supersedes,
        List<String> producesArtifacts,
        String description) {

    public NodeDefinition {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        producesArtifacts = producesArtifacts == null ? List.of() : List.copyOf(producesArtifacts);
        joinType = joinType == null ? JoinType.ALL : joinType;
        recoveryMode = recoveryMode == null ? RecoveryMode.NONE : recoveryMode;
        criticality = criticality == null ? Criticality.BLOCKING : criticality;
        maxAttempts = maxAttempts <= 0 ? 1 : maxAttempts;
        timeoutMs = timeoutMs <= 0 ? 30_000 : timeoutMs;
        backoffBaseMs = backoffBaseMs <= 0 ? 1_000 : backoffBaseMs;
    }

    /** True when this node declares an exit gate, i.e. */
    public boolean hasExitGate() {
        return !producesArtifacts.isEmpty();
    }

    public String effectiveGateId() {
        return gateId == null || gateId.isBlank() ? id : gateId;
    }
}
