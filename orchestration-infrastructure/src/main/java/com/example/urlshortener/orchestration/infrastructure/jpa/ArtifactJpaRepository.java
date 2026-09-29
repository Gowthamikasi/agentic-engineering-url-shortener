package com.example.urlshortener.orchestration.infrastructure.jpa;

import com.example.urlshortener.orchestration.infrastructure.ControlPlaneEntities;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for persisted node outputs. */
public interface ArtifactJpaRepository extends JpaRepository<ControlPlaneEntities.ArtifactEntity, String> {

    List<ControlPlaneEntities.ArtifactEntity> findByRunIdOrderByProducedAtAsc(String runId);
}
