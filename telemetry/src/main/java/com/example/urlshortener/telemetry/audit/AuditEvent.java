package com.example.urlshortener.telemetry.audit;

import java.time.Instant;

/**
 * One append-only audit row.
 *
 * <p>prevHash and hash form a per-run chain: each row commits to its own content plus the
 * previous row's hash, so editing or removing one breaks every hash after it. Tamper-evident,
 * not tamper-proof.
 */
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
