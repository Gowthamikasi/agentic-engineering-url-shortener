package com.example.urlshortener.policy;

import java.time.Instant;

/** An approved deviation from a mandatory rule (REQ-D-006). */
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
