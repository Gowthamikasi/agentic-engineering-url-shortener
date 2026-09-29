package com.example.urlshortener.orchestration.port;

import com.example.urlshortener.orchestration.model.Artifact;

import java.util.List;

/** Durable store for node outputs, so lineage survives a restart. */
public interface ArtifactStore {

    void save(String runId, Artifact artifact);

    List<Artifact> findByRun(String runId);
}
