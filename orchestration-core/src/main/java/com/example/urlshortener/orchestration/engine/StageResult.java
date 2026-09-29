package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.FailureClass;

import java.util.List;
import java.util.Map;

/**
 * What an agent reports back.
 *
 * <p>An agent states the failure class it believes applies; the engine may override it upward to
 * {@code PERMANENT} but never downward, so an agent cannot talk the engine into retrying
 * something the engine considers unsafe to repeat.
 */
public record StageResult(
        Outcome outcome,
        FailureClass failureClass,
        String message,
        List<ArtifactDraft> artifacts,
        Map<String, Object> facts,
        List<String> assumptions) {

    public enum Outcome {
        SUCCESS,
        FAILURE
    }

    /** An artifact an agent wants recorded; the engine assigns the id, version and hash. */
    public record ArtifactDraft(String type, String contentJson, List<String> inputArtifactIds) {

        public ArtifactDraft {
            inputArtifactIds = inputArtifactIds == null ? List.of() : List.copyOf(inputArtifactIds);
        }

        public static ArtifactDraft of(String type, String contentJson) {
            return new ArtifactDraft(type, contentJson, List.of());
        }
    }

    public StageResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    public boolean succeeded() {
        return outcome == Outcome.SUCCESS;
    }

    public static Builder success(String message) {
        return new Builder(Outcome.SUCCESS, null, message);
    }

    public static Builder failure(FailureClass failureClass, String message) {
        return new Builder(Outcome.FAILURE, failureClass, message);
    }

    public static final class Builder {

        private final Outcome outcome;
        private final FailureClass failureClass;
        private final String message;
        private final List<ArtifactDraft> artifacts = new java.util.ArrayList<>();
        private final Map<String, Object> facts = new java.util.LinkedHashMap<>();
        private final List<String> assumptions = new java.util.ArrayList<>();

        private Builder(Outcome outcome, FailureClass failureClass, String message) {
            this.outcome = outcome;
            this.failureClass = failureClass;
            this.message = message;
        }

        public Builder artifact(String type, String contentJson, List<String> inputArtifactIds) {
            artifacts.add(new ArtifactDraft(type, contentJson, inputArtifactIds));
            return this;
        }

        public Builder fact(String key, Object value) {
            facts.put(key, value);
            return this;
        }

        public Builder facts(Map<String, Object> more) {
            facts.putAll(more);
            return this;
        }

        public Builder assumption(String assumption) {
            assumptions.add(assumption);
            return this;
        }

        public StageResult build() {
            return new StageResult(outcome, failureClass, message, artifacts, facts, assumptions);
        }
    }
}
