package com.example.urlshortener.orchestration.infrastructure.jpa;

import com.example.urlshortener.orchestration.infrastructure.ControlPlaneEntities;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** Spring Data repository for the append-only transition journal. */
public interface JournalJpaRepository extends JpaRepository<ControlPlaneEntities.JournalEntity, Long> {

    List<ControlPlaneEntities.JournalEntity> findByRunIdOrderBySeqAsc(String runId);

    List<ControlPlaneEntities.JournalEntity> findAllByOrderByRunIdAscSeqAsc();

    @Query("select distinct j.runId from JournalEntity j")
    List<String> findDistinctRunIds();
}
