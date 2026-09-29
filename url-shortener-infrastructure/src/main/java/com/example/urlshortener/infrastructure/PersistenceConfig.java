package com.example.urlshortener.infrastructure;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Tells Spring where this module's entities and repositories live. */
@Configuration
@EnableJpaRepositories(basePackages = "com.example.urlshortener.infrastructure.jpa")
@EntityScan(basePackages = "com.example.urlshortener.infrastructure.entity")
public class PersistenceConfig {
}
