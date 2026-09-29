package com.example.urlshortener.orchestration.infrastructure.jpa;

import com.example.urlshortener.orchestration.infrastructure.ControlPlaneEntities;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for recorded human decisions. */
public interface ApprovalJpaRepository extends JpaRepository<ControlPlaneEntities.ApprovalEntity, String> {

    List<ControlPlaneEntities.ApprovalEntity> findByRunIdOrderByDecidedAtAsc(String runId);

    List<ControlPlaneEntities.ApprovalEntity> findByRunIdAndGateIdOrderByDecidedAtAsc(String runId, String gateId);
}
