package com.example.urlshortener.domain;

import com.example.urlshortener.domain.model.ShortLink;

import java.time.Instant;

/**
 * ASM-004: expiry is an optional absolute UTC instant, there is no default expiry, and an
 * expired link resolves to {@code Gone} rather than {@code NotFound} so callers can tell the
 * difference between "never existed" and "existed and lapsed".
 */
public final class ExpiryPolicy {

    private ExpiryPolicy() {
    }

    /** Result of resolving a link against the clock. */
    public enum Resolution {
        ACTIVE,
        GONE
    }

    public static Resolution resolve(ShortLink link, Instant now) {
        return link.isExpired(now) ? Resolution.GONE : Resolution.ACTIVE;
    }

    /** A requested expiry must be strictly in the future; anything else is a client error. */
    public static boolean isAcceptableExpiry(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }
}
