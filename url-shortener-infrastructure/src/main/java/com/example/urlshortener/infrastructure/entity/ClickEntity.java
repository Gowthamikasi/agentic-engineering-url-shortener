package com.example.urlshortener.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for {@code clicks}. */
@Entity
@Table(name = "clicks")
public class ClickEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", length = 16, nullable = false)
    private String code;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "referer_host")
    private String refererHost;

    @Column(name = "ua_class", length = 16)
    private String uaClass;

    protected ClickEntity() {
    }

    public ClickEntity(String code, Instant occurredAt, String refererHost, String uaClass) {
        this.code = code;
        this.occurredAt = occurredAt;
        this.refererHost = refererHost;
        this.uaClass = uaClass;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getRefererHost() {
        return refererHost;
    }

    public String getUaClass() {
        return uaClass;
    }
}
