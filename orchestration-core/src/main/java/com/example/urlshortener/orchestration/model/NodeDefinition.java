package com.example.urlshortener.orchestration.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One node of a workflow definition, as loaded from versioned JSON.
 *
 * <p>Retry, timeout and recovery are declared here rather than implemented inside agents. That
 * split is deliberate: if an agent could retry internally, the engine's retry budget would be a
 * lie and the journal would not show what actually happened.
 *
 * @param id              stable node id, unique within the definition
 * @param agentType       which registered agent executes this node
 * @param dependsOn       upstream node ids
 * @param joinType        whether all or any dependency must be satisfied
 * @param requiresApproval whether this node is a human gate
 * @param gateId          the gate identifier callers post decisions to
 * @param timeoutMs       per-attempt timeout
 * @param maxAttempts     total attempts, including the first (1 means no retry)
 * @param backoffBaseMs   base for exponential backoff between attempts
 * @param recoveryMode    what can be undone if this node fails
 * @param criticality     whether failure blocks the downstream subgraph
 * @param branchCondition SpEL over the run context; a false condition skips the node
 * @param fallbackAllowed whether a permanent failure may be absorbed as a degraded success
 * @param destructive     whether the node performs an irreversible action and needs its own gate
 * @param supersedes      for a gate: the upstream node whose artifact this gate's decision replaces.
 *                        Setting it is what turns an approval into a replanning trigger, so the
 *                        definition states which decisions can reshape the graph instead of the
 *                        engine inferring it.
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
        String description) {

    public NodeDefinition {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        joinType = joinType == null ? JoinType.ALL : joinType;
        recoveryMode = recoveryMode == null ? RecoveryMode.NONE : recoveryMode;
        criticality = criticality == null ? Criticality.BLOCKING : criticality;
        maxAttempts = maxAttempts <= 0 ? 1 : maxAttempts;
        timeoutMs = timeoutMs <= 0 ? 30_000 : timeoutMs;
        backoffBaseMs = backoffBaseMs <= 0 ? 1_000 : backoffBaseMs;
    }

    public String effectiveGateId() {
        return gateId == null || gateId.isBlank() ? id : gateId;
    }
}
