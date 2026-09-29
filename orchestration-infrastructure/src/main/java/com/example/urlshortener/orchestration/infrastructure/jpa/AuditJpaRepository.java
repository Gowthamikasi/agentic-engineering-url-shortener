package com.example.urlshortener.orchestration.infrastructure.jpa;

import com.example.urlshortener.orchestration.infrastructure.ControlPlaneEntities;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for the hash-chained audit trail. */
public interface AuditJpaRepository extends JpaRepository<ControlPlaneEntities.AuditEntity, Long> {

    List<ControlPlaneEntities.AuditEntity> findByRunIdOrderBySeqAsc(String runId);

    List<ControlPlaneEntities.AuditEntity> findAllByOrderByRunIdAscSeqAsc();
}
