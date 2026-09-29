package com.example.urlshortener.infrastructure.adapter;

import com.example.urlshortener.domain.model.IdempotencyRecord;
import com.example.urlshortener.domain.port.IdempotencyRepository;
import com.example.urlshortener.infrastructure.entity.IdempotencyEntity;
import com.example.urlshortener.infrastructure.jpa.IdempotencyJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Adapts the idempotency ledger port to Spring Data JPA. */
@Repository
public class JpaIdempotencyRepository implements IdempotencyRepository {

    private final IdempotencyJpaRepository jpa;

    public JpaIdempotencyRepository(IdempotencyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(String key) {
        return jpa.findById(key)
                .map(e -> new IdempotencyRecord(e.getKey(), e.getRequestHash(), e.getCode(), e.getCreatedAt()));
    }

    @Override
    @Transactional
    public void save(IdempotencyRecord record) {
        jpa.save(new IdempotencyEntity(record.key(), record.requestHash(), record.code(), record.createdAt()));
    }
}
