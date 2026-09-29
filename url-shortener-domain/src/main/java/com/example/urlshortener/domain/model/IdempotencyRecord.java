package com.example.urlshortener.domain.model;

import java.time.Instant;

/**
 * ASM-002: same Idempotency-Key plus the same request body returns the original link;
 * same key with a different body is a 409 conflict. The body is compared by hash, never stored raw.
 */
public record IdempotencyRecord(String key, String requestHash, String code, Instant createdAt) {
}
