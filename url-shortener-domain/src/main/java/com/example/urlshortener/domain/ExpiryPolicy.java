package com.example.urlshortener.domain;

import com.example.urlshortener.domain.model.ShortLink;

import java.time.Instant;

/**
 * Expiry is an optional absolute UTC instant and there is no default (ASM-004).
 *
 * <p>An expired link is Gone rather than NotFound, so a caller can tell "never existed" from
 * "existed and lapsed".
 */
public final class ExpiryPolicy {

    private ExpiryPolicy() {
    }

    /** Whether a link is still live at a given moment. */
    public enum Resolution {
        ACTIVE,
        GONE
    }

    public static Resolution resolve(ShortLink link, Instant now) {
        return link.isExpired(now) ? Resolution.GONE : Resolution.ACTIVE;
    }

    /** An expiry has to be in the future to be accepted. */
    public static boolean isAcceptableExpiry(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }
}
