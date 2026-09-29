package com.example.urlshortener.domain.port;

import com.example.urlshortener.domain.model.IdempotencyRecord;

import java.util.Optional;

/** Outbound port for the Idempotency-Key ledger (ASM-002). */
public interface IdempotencyRepository {

    Optional<IdempotencyRecord> find(String key);

    void save(IdempotencyRecord record);
}
