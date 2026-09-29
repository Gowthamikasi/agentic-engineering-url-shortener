package com.example.urlshortener.telemetry.audit;

import java.time.Instant;

/** One append-only audit row. */
public record AuditEvent(
        String runId,
        long seq,
        Instant timestamp,
        ActorType actorType,
        String actorId,
        String action,
        String targetType,
        String targetId,
        String result,
        String reason,
        String policyVersion,
        long definitionVersion,
        String prevHash,
        String hash) {

    public static final String GENESIS_HASH = "0".repeat(64);
}
