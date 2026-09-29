package com.example.urlshortener.orchestration.model;

import java.time.Instant;

/**
 * One journal row: the atom the whole control plane is reconstructed from.
 *
 * <p>{@code (runId, seq)} is unique, which is what stops a restarted scheduler from appending a
 * second copy of an event it already wrote.
 *
 * @param action     one of {@code AuditActions}; the closed vocabulary the metrics calculator reads
 * @param actorType  HUMAN, AGENT, ENGINE or SYSTEM
 * @param payloadJson optional structured detail, e.g. an approval body or a failure report
 */
public record TransitionEvent(
        String runId,
        long seq,
        String nodeId,
        NodeState fromState,
        NodeState toState,
        String action,
        String actorType,
        String actorId,
        String result,
        String reason,
        long definitionVersion,
        String policyVersion,
        Instant timestamp,
        String payloadJson) {
}
