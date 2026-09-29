package com.example.urlshortener.infrastructure.jpa;

import com.example.urlshortener.infrastructure.entity.ShortLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortLinkJpaRepository extends JpaRepository<ShortLinkEntity, String> {
}
