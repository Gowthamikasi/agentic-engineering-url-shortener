package com.example.urlshortener.orchestration.infrastructure.jpa;

import com.example.urlshortener.orchestration.infrastructure.ControlPlaneEntities;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@code InstanceEntity}. */
public interface InstanceJpaRepository extends JpaRepository<ControlPlaneEntities.InstanceEntity, String> {
}
