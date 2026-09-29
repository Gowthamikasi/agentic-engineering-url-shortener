package com.example.urlshortener.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A shortened link. Plain domain type: no Spring, no JPA.
 *
 * @param code           the short code
 * @param target         canonicalised absolute target URL
 * @param createdAt      creation instant (UTC)
 * @param expiresAt      optional absolute expiry (UTC); null means it never expires
 * @param idempotencyKey the Idempotency-Key that minted it, if any
 * @param createdByKeyId which API key created it (the id, never the key)
 */
public record ShortLink(
        String code,
        URI target,
        Instant createdAt,
        Instant expiresAt,
        String idempotencyKey,
        String createdByKeyId) {

    public ShortLink {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** ASM-004: expiry is an absolute instant; a link is expired once {@code now} is at or after it. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }
}
