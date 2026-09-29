package com.example.urlshortener.orchestration.model;

import java.time.Instant;
import java.util.List;

/** Current view of one node, derived from the journal. */
public record NodeSnapshot(
        String id,
        NodeState state,
        int attempts,
        Instant startedAt,
        Instant endedAt,
        Instant nextAttemptAt,
        String reason,
        FailureClass lastFailureClass,
        String lastFailureReason,
        List<String> waitingOn,
        List<String> artifactIds,
        boolean degraded) {

    public NodeSnapshot {
        waitingOn = waitingOn == null ? List.of() : List.copyOf(waitingOn);
        artifactIds = artifactIds == null ? List.of() : List.copyOf(artifactIds);
    }
}
