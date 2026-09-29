package com.example.urlshortener.policy;

import java.time.Instant;

/**
 * An approved deviation from a mandatory rule (REQ-D-006).
 *
 * <p>Every field here exists because leaving it out is how exceptions become permanent:
 * an exception names the rule it waives, why, how far it reaches, who approved it, what
 * compensating control stands in for the rule, and when it lapses. An expired exception is
 * re-evaluated as a plain FAIL — it does not decay into a PASS.
 */
public record PolicyException(
        String id,
        String policyId,
        String reason,
        String scope,
        String approver,
        String compensatingControl,
        Instant approvedAt,
        Instant expiresAt,
        String reviewCondition) {

    public boolean isActiveAt(Instant now) {
        if (approver == null || approvedAt == null) {
            return false;
        }
        return expiresAt == null || now.isBefore(expiresAt);
    }

    public boolean isExpiredAt(Instant now) {
        return approvedAt != null && expiresAt != null && !now.isBefore(expiresAt);
    }
}
