package com.example.urlshortener.infrastructure.adapter;

import com.example.urlshortener.domain.model.ShortLink;
import com.example.urlshortener.domain.port.ShortLinkRepository;
import com.example.urlshortener.infrastructure.entity.ShortLinkEntity;
import com.example.urlshortener.infrastructure.jpa.ShortLinkJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Optional;

/** Adapts the domain port to Spring Data JPA. */
@Repository
public class JpaShortLinkRepository implements ShortLinkRepository {

    private final ShortLinkJpaRepository jpa;
    private final EntityManager entityManager;

    public JpaShortLinkRepository(ShortLinkJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShortLink> findByCode(String code) {
        return jpa.findById(code).map(JpaShortLinkRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCode(String code) {
        return jpa.existsById(code);
    }

    /**
     * Inserts only when the code is free.
     *
     * <p>Two details here are load-bearing. {@code persist} is used rather than
     * {@code JpaRepository.save}: the entity has an assigned string id, so {@code save} would
     * treat it as detached and <em>merge</em> it, quietly overwriting an existing link instead of
     * reporting the collision. And {@code REQUIRES_NEW} keeps a losing race out of the caller's
     * transaction, because the caller's answer to a collision is simply to mint another code —
     * which it could not do inside a transaction the constraint violation had already poisoned.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveIfAbsent(ShortLink link) {
        if (jpa.existsById(link.code())) {
            return false;
        }
        try {
            entityManager.persist(new ShortLinkEntity(link.code(), link.target().toString(), link.createdAt(),
                    link.expiresAt(), link.idempotencyKey(), link.createdByKeyId()));
            entityManager.flush();
            return true;
        } catch (DataIntegrityViolationException | PersistenceException e) {
            // Another writer took the code between the check and the insert.
            entityManager.clear();
            return false;
        }
    }

    @Override
    @Transactional
    public boolean deleteByCode(String code) {
        if (!jpa.existsById(code)) {
            return false;
        }
        jpa.deleteById(code);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return jpa.count();
    }

    private static ShortLink toDomain(ShortLinkEntity e) {
        return new ShortLink(e.getCode(), URI.create(e.getTarget()), e.getCreatedAt(), e.getExpiresAt(),
                e.getIdempotencyKey(), e.getCreatedBy());
    }
}
