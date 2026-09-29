package com.example.urlshortener.orchestration.model;

import java.time.Instant;
import java.util.List;

/**
 * A versioned output of a node.
 *
 * <p>Downstream nodes receive artifact <em>references</em>, never copies, and each artifact records
 * the inputs and decisions that produced it. Walking those links backwards from any artifact
 * reaches the original requirement, which is what makes lineage a property of the data rather
 * than something reconstructed from logs after the fact.
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
