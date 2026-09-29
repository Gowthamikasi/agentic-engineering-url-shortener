package com.example.urlshortener.orchestration.model;

import java.time.Instant;
import java.util.List;

/** A recorded decision, human or engine. The rationale is required, not decorative. */
public record Decision(
        String decisionId,
        String gateId,
        String type,
        String actor,
        String decision,
        String rationale,
        String conditions,
        List<String> affectedArtifacts,
        Instant timestamp) {

    public Decision {
        affectedArtifacts = affectedArtifacts == null ? List.of() : List.copyOf(affectedArtifacts);
    }
}
