package com.example.urlshortener.domain.model;

import java.time.Instant;

/**
 * Ledger for Idempotency-Key (ASM-002).
 *
 * <p>Same key and same body replays the original link; same key with a different body is a
 * conflict. The body is compared by hash and never stored raw.
 */
public record IdempotencyRecord(String key, String requestHash, String code, Instant createdAt) {
}
