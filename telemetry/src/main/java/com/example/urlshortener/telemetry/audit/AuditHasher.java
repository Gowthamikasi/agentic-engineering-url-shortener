package com.example.urlshortener.telemetry.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes and verifies the per-run audit hash chain. */
public final class AuditHasher {

    private AuditHasher() {
    }

    /** Canonical serialisation of the fields the chain commits to. Field order is part of the contract. */
    public static String canonicalPayload(AuditEvent e) {
        return String.join("\u001f",
                nullSafe(e.runId()),
                Long.toString(e.seq()),
                e.timestamp() == null ? "" : e.timestamp().toString(),
                e.actorType() == null ? "" : e.actorType().name(),
                nullSafe(e.actorId()),
                nullSafe(e.action()),
                nullSafe(e.targetType()),
                nullSafe(e.targetId()),
                nullSafe(e.result()),
                nullSafe(e.reason()),
                nullSafe(e.policyVersion()),
                Long.toString(e.definitionVersion()),
                nullSafe(e.prevHash()));
    }

    public static String hash(AuditEvent event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(canonicalPayload(event).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Returns the event with {@code hash} filled in from its content and {@code prevHash}. */
    public static AuditEvent seal(AuditEvent unsealed) {
        return new AuditEvent(unsealed.runId(), unsealed.seq(), unsealed.timestamp(), unsealed.actorType(),
                unsealed.actorId(), unsealed.action(), unsealed.targetType(), unsealed.targetId(),
                unsealed.result(), unsealed.reason(), unsealed.policyVersion(), unsealed.definitionVersion(),
                unsealed.prevHash(), hash(unsealed));
    }

    /**
     * Verifies that a run's events form an unbroken chain in sequence order.
     *
     * @return the 1-based index of the first broken row, or -1 when the chain is intact
     */
    public static int verifyChain(java.util.List<AuditEvent> eventsInSeqOrder) {
        String expectedPrev = AuditEvent.GENESIS_HASH;
        for (int i = 0; i < eventsInSeqOrder.size(); i++) {
            AuditEvent e = eventsInSeqOrder.get(i);
            if (!expectedPrev.equals(e.prevHash()) || !hash(e).equals(e.hash())) {
                return i + 1;
            }
            expectedPrev = e.hash();
        }
        return -1;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
