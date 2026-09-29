package com.example.urlshortener.orchestration.model;

import java.time.Instant;

/**
 * One journal row. The whole control plane is rebuilt from these.
 *
 * <p>(runId, seq) is unique, which stops a restarted scheduler writing an event twice.
 *
 * @param action      one of AuditActions; the metrics calculator reads this vocabulary
 * @param actorType   HUMAN, AGENT, ENGINE or SYSTEM
 * @param payloadJson optional detail, such as an approval body or a failure report
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
