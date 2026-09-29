package com.example.urlshortener.infrastructure;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Declares where this module's entities and repositories live.
 *
 * <p>Without this, Spring Boot would only scan the package of the application class, which sits in
 * a different module. Declaring it here keeps the knowledge inside the module that owns it: the
 * host application does not have to know how this adapter is laid out.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.example.urlshortener.infrastructure.jpa")
@EntityScan(basePackages = "com.example.urlshortener.infrastructure.entity")
public class PersistenceConfig {
}
