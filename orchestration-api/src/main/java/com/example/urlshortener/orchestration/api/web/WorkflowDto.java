package com.example.urlshortener.orchestration.api.web;

import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Decision;
import com.example.urlshortener.orchestration.model.NodeSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response shapes for the control-plane API. */
public final class WorkflowDto {

    private WorkflowDto() {
    }

    /** Start a run. {@code kind} drives the brownfield branch; {@code faults} injects failures for a demo. */
    public record StartRunRequest(
            String definition,
            @NotBlank(message = "input.text is required") String text,
            String requirementId,
            String kind,
            @NotBlank(message = "actor is required") String actor,
            String policyVersion,
            Map<String, Object> faults,
            Map<String, Object> dependencyScan,
            Map<String, Object> tddEvidence) {
    }

    /** A human decision at a gate. Actor and rationale are both required. */
    public record DecisionRequest(
            @NotBlank(message = "decision is required") String decision,
            @NotBlank(message = "actor is required") String actor,
            @NotBlank(message = "rationale is required")
            @Size(min = 10, message = "rationale must be a real explanation, not a placeholder")
            String rationale,
            String conditions) {
    }

    public record ResumeRequest(
            @NotBlank(message = "actor is required") String actor,
            @NotBlank(message = "reason is required") String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunSummary(String runId, String definitionName, long definitionVersion, String policyVersion,
                             String state, String terminalOutcome, Instant createdAt, Instant terminalAt,
                             Map<String, String> links) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeView(String id, String state, int attempts, Instant startedAt, Instant endedAt,
                           Instant nextAttemptAt, String reason, String lastFailureClass, String lastFailureReason,
                           List<String> waitingOn, List<String> artifactIds, boolean degraded) {

        public static NodeView of(NodeSnapshot snapshot) {
            return new NodeView(snapshot.id(), snapshot.state().name(), snapshot.attempts(), snapshot.startedAt(),
                    snapshot.endedAt(), snapshot.nextAttemptAt(), snapshot.reason(),
                    snapshot.lastFailureClass() == null ? null : snapshot.lastFailureClass().name(),
                    snapshot.lastFailureReason(), snapshot.waitingOn(), snapshot.artifactIds(), snapshot.degraded());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunDetail(String runId, String definitionName, long definitionVersion, String policyVersion,
                            String state, String terminalOutcome, Instant createdAt, Instant terminalAt,
                            boolean releaseBlocked, List<NodeView> nodes, List<DecisionView> decisions,
                            List<String> assumptions, List<String> replanning, Map<String, Object> facts) {
    }

    public record DecisionView(String decisionId, String gateId, String decision, String actor, String rationale,
                               String conditions, Instant at) {

        public static DecisionView of(Decision decision) {
            return new DecisionView(decision.decisionId(), decision.gateId(), decision.decision(),
                    decision.actor(), decision.rationale(), decision.conditions(), decision.timestamp());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GateView(String gateId, String nodeId, String state, Instant requestedAt, Instant expiresAt,
                           String description, List<String> contextArtifacts, String decision, String actor) {
    }

    public record ArtifactView(String artifactId, String nodeId, String type, int version, String sha256,
                               List<String> inputArtifactIds, List<String> decisionIds, Instant producedAt,
                               Object content) {
    }

    /** One step of a lineage walk, back towards the original requirement. */
    public record LineageStep(String artifactId, String nodeId, String type, int version, String sha256,
                              List<String> inputArtifactIds, List<String> decisionIds, Instant producedAt) {

        public static LineageStep of(Artifact artifact) {
            return new LineageStep(artifact.artifactId(), artifact.nodeId(), artifact.type(), artifact.version(),
                    artifact.sha256(), artifact.inputArtifactIds(), artifact.decisionIds(), artifact.producedAt());
        }
    }

    public record LineageResponse(String runId, String artifactId, List<LineageStep> chain,
                                  List<DecisionView> decisions) {
    }
}
