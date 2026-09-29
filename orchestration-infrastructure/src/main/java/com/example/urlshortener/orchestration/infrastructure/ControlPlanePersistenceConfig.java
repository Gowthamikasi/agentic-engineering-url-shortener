package com.example.urlshortener.orchestration.infrastructure;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Declares where the control-plane entities and repositories live. */
@Configuration
@EnableJpaRepositories(basePackages = "com.example.urlshortener.orchestration.infrastructure.jpa")
@EntityScan(basePackageClasses = ControlPlaneEntities.class)
public class ControlPlanePersistenceConfig {
}
