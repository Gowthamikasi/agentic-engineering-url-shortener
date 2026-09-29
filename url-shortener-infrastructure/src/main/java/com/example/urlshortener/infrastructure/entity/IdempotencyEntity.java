package com.example.urlshortener.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for {@code idempotency}. */
@Entity
@Table(name = "idempotency")
public class IdempotencyEntity {

    @Id
    @Column(name = "idem_key", nullable = false)
    private String key;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "code", length = 16, nullable = false)
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyEntity() {
    }

    public IdempotencyEntity(String key, String requestHash, String code, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.code = code;
        this.createdAt = createdAt;
    }

    public String getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getCode() {
        return code;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
