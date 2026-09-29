package com.example.urlshortener.domain.model;

import java.time.Instant;

/** Ledger for Idempotency-Key (ASM-002). */
public record IdempotencyRecord(String key, String requestHash, String code, Instant createdAt) {
}
