package com.example.urlshortener.infrastructure.jpa;

import com.example.urlshortener.infrastructure.entity.ClickEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClickJpaRepository extends JpaRepository<ClickEntity, Long> {

    long countByCode(String code);

    @Query("select count(c) from ClickEntity c where c.code = :code and c.occurredAt >= :from and c.occurredAt < :to")
    long countInWindow(@Param("code") String code, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select max(c.occurredAt) from ClickEntity c where c.code = :code")
    Instant lastClickAt(@Param("code") String code);

    @Query("select c from ClickEntity c where c.code = :code and c.occurredAt >= :from and c.occurredAt < :to order by c.occurredAt")
    List<ClickEntity> findInWindow(@Param("code") String code, @Param("from") Instant from, @Param("to") Instant to);
}
