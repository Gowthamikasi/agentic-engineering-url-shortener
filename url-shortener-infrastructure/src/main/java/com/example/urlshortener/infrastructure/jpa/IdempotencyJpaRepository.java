package com.example.urlshortener.infrastructure.jpa;

import com.example.urlshortener.infrastructure.entity.IdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyJpaRepository extends JpaRepository<IdempotencyEntity, String> {
}
