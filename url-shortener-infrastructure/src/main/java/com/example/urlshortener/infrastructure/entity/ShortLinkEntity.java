package com.example.urlshortener.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for {@code links}. The domain record stays free of persistence concerns. */
@Entity
@Table(name = "links")
public class ShortLinkEntity {

    @Id
    @Column(name = "code", length = 16, nullable = false)
    private String code;

    @Column(name = "target", length = 2048, nullable = false)
    private String target;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    protected ShortLinkEntity() {
    }

    public ShortLinkEntity(String code, String target, Instant createdAt, Instant expiresAt,
                           String idempotencyKey, String createdBy) {
        this.code = code;
        this.target = target;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey;
        this.createdBy = createdBy;
    }

    public String getCode() {
        return code;
    }

    public String getTarget() {
        return target;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
