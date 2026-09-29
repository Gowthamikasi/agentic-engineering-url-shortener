package com.example.urlshortener.orchestration.infrastructure.jpa;

import com.example.urlshortener.orchestration.infrastructure.ControlPlaneEntities;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@code PolicyExceptionEntity}. */
public interface PolicyExceptionJpaRepository extends JpaRepository<ControlPlaneEntities.PolicyExceptionEntity, String> {
}
