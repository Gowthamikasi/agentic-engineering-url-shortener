package com.example.urlshortener.telemetry.audit;

import java.time.Instant;

/**
 * One append-only audit row.
 *
 * <p>{@code prevHash}/{@code hash} form a per-run hash chain: each row commits to its own content
 * plus the previous row's hash, so a row cannot be edited or removed from the middle of a run
 * without breaking every subsequent hash. This is demo-grade tamper evidence, not a ledger.
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
