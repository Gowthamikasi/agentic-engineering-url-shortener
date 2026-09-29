package com.example.urlshortener.orchestration.model;

import java.time.Instant;
import java.util.List;

/**
 * A versioned output of a node.
 *
 * <p>Each artifact records the artifacts and decisions it came from, so lineage can be walked
 * back to the original requirement.
 */
public record Artifact(
        String artifactId,
        String nodeId,
        String type,
        int version,
        String sha256,
        String contentJson,
        List<String> inputArtifactIds,
        List<String> decisionIds,
        Instant producedAt,
        boolean degraded) {

    public Artifact {
        inputArtifactIds = inputArtifactIds == null ? List.of() : List.copyOf(inputArtifactIds);
        decisionIds = decisionIds == null ? List.of() : List.copyOf(decisionIds);
    }
}
