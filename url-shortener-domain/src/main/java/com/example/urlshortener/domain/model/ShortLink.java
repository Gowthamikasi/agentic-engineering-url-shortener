package com.example.urlshortener.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A shortened link. Pure domain type: no Spring, no JPA, no framework annotations.
 *
 * @param code            the short code (application-plane primary key)
 * @param target          canonicalised absolute target URL
 * @param createdAt       creation instant (UTC)
 * @param expiresAt       optional absolute expiry (UTC); {@code null} means "never expires" (ASM-004)
 * @param idempotencyKey  optional client-supplied Idempotency-Key that minted this link (ASM-002)
 * @param createdByKeyId  identifier of the API key that created the link (never the key itself)
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
