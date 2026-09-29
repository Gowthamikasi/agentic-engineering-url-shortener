package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Decision;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything an agent is allowed to see.
 *
 * <p>Note what is absent: no {@code DataSource}, no repository, no API key, no file-system root.
 * An agent receives artifact references and facts and returns artifacts and facts. That boundary
 * is what makes it safe to say an agent cannot reach outside its stage, and it is enforced by the
 * type rather than by convention.
 *
 * @param runId             correlation id for the whole run
 * @param nodeId            node being executed
 * @param attempt           1-based attempt number within this node's attempt series
 * @param definitionVersion definition version this attempt belongs to
 * @param policyVersion     policy set version stamped on the run
 * @param input             the original workflow input
 * @param upstreamArtifacts artifacts produced by satisfied dependencies
 * @param decisions         decisions recorded so far, newest last
 * @param assumptions       assumptions recorded so far
 * @param facts             accumulated facts, later handed to the policy evaluator
 */
public record StageContext(
        String runId,
        String nodeId,
        int attempt,
        long definitionVersion,
        String policyVersion,
        Map<String, Object> input,
        List<Artifact> upstreamArtifacts,
        List<Decision> decisions,
        List<String> assumptions,
        Map<String, Object> facts) {

    public StageContext {
        input = input == null ? Map.of() : Map.copyOf(input);
        upstreamArtifacts = upstreamArtifacts == null ? List.of() : List.copyOf(upstreamArtifacts);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public Optional<Artifact> upstreamOfType(String type) {
        return upstreamArtifacts.stream().filter(a -> type.equals(a.type())).reduce((first, second) -> second);
    }

    public List<String> upstreamArtifactIds() {
        return upstreamArtifacts.stream().map(Artifact::artifactId).toList();
    }

    public String inputText(String key) {
        Object value = input.get(key);
        return value == null ? null : value.toString();
    }

    public Optional<Decision> decisionForGate(String gateId) {
        return decisions.stream().filter(d -> gateId.equals(d.gateId())).reduce((first, second) -> second);
    }
}
