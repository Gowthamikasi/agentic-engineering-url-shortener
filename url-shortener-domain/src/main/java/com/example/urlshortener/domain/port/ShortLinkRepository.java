package com.example.urlshortener.domain.port;

import com.example.urlshortener.domain.model.ShortLink;

import java.util.Optional;

/** Outbound port for short-link persistence. */
public interface ShortLinkRepository {

    Optional<ShortLink> findByCode(String code);

    boolean existsByCode(String code);

    /** @return false if the code was already taken, which the caller treats as a collision. */
    boolean saveIfAbsent(ShortLink link);

    boolean deleteByCode(String code);

    long count();
}
