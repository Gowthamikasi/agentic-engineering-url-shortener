package com.example.urlshortener.orchestration.agents;

import com.example.urlshortener.orchestration.engine.StageContext;
import com.example.urlshortener.orchestration.engine.StageResult;
import com.example.urlshortener.orchestration.model.Decision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clarification decision, pinned against the three scenario inputs.
 *
 * <p>REQ-D-009 cuts both ways and both directions are tested here: a clear requirement must not be
 * stopped by a gate that exists only to look careful, and a genuinely ambiguous one must not be
 * waved through. A system that asks about everything trains its reviewers to approve without
 * reading, which is worse than a system that never asks.
 */
class RequirementQualityAgentTest {

    private static final String SCENARIO_A = """
            A client may supply expiresAt (ISO-8601 UTC) when creating a link. A redirect for an \
            expired link returns 410 Gone with a JSON problem body. An expiresAt in the past is \
            rejected with 400. Stats must still show total clicks for an expired link.""";

    private static final String SCENARIO_B = """
            New codes must exclude the visually ambiguous characters 0, O, I and l. Existing base62 \
            codes must continue to resolve. Validation must accept both alphabets for lookup but \
            only base58 for newly minted codes.""";

    private static final String SCENARIO_C =
            "Links should expire after a while and we should show popular links.";

    private final ObjectMapper json = new ObjectMapper();
    private final RequirementAgents.RequirementQualityAgent agent =
            new RequirementAgents.RequirementQualityAgent(json);

    private StageContext contextFor(String text, List<Decision> decisions) {
        return new StageContext("run_test", "quality-check", 1, 1, "1.0.0",
                Map.of("text", text, "kind", "Greenfield", "actor", "madhu"),
                List.of(), decisions, List.of(), Map.of());
    }

    private String decisionFor(String text) {
        StageResult result = agent.execute(contextFor(text, List.of()));
        return String.valueOf(result.facts().get("quality.decision"));
    }

    @Test
    void a_well_specified_greenfield_requirement_proceeds_without_a_clarification_gate() {
        StageResult result = agent.execute(contextFor(SCENARIO_A, List.of()));

        assertThat(result.facts()).containsEntry("quality.decision", "NoClarificationRequired");
        assertThat(result.facts()).containsEntry("quality.ambiguityCount", 0);
        assertThat(result.message()).contains("5/5 quality checks pass");
    }

    @Test
    void a_well_specified_brownfield_requirement_also_proceeds_without_a_gate() {
        assertThat(decisionFor(SCENARIO_B)).isEqualTo("NoClarificationRequired");
    }

    @Test
    void a_genuinely_vague_requirement_arms_the_clarification_gate() {
        StageResult result = agent.execute(contextFor(SCENARIO_C, List.of()));

        assertThat(result.facts()).containsEntry("quality.decision", "ClarificationRequired");
        assertThat((Integer) result.facts().get("quality.ambiguityCount")).isGreaterThanOrEqualTo(3);
        assertThat(result.assumptions()).isNotEmpty();
    }

    @Test
    void the_detected_ambiguities_name_what_is_actually_missing() {
        List<String> ambiguities = RequirementAgents.RequirementQualityAgent.detectAmbiguities(SCENARIO_C);

        assertThat(ambiguities).anyMatch(a -> a.contains("a while"));
        assertThat(ambiguities).anyMatch(a -> a.contains("popular"));
        assertThat(ambiguities).anyMatch(a -> a.contains("show"));
    }

    @Test
    void a_default_expiry_is_reported_as_conflicting_with_the_approved_no_default_baseline() {
        assertThat(RequirementAgents.RequirementQualityAgent.conflictsWithApprovedBaseline(SCENARIO_C)).isTrue();
        assertThat(RequirementAgents.RequirementQualityAgent.conflictsWithApprovedBaseline(SCENARIO_A)).isFalse();
    }

    /**
     * Without this, the clarify gate would re-arm on every replan and the run would never
     * terminate: the decision changes the requirement, the requirement is re-checked, and the
     * check would ask the same question again.
     */
    @Test
    void once_a_clarification_decision_exists_the_gate_is_not_armed_again() {
        Decision clarification = new Decision("dec_1", "clarify", "GateDecision", "madhu", "APPROVE",
                "Default expiry is 90 days when expiresAt is omitted; popular means the top 10 by clicks "
                        + "in the trailing 7 days, behind an authenticated endpoint.",
                null, List.of(), Instant.now());

        StageResult result = agent.execute(contextFor(SCENARIO_C, List.of(clarification)));

        assertThat(result.facts()).containsEntry("quality.decision", "NoClarificationRequired");
    }

    @Test
    void an_empty_requirement_is_ambiguous_rather_than_silently_acceptable() {
        assertThat(RequirementAgents.RequirementQualityAgent.detectAmbiguities(null)).isNotEmpty();
        assertThat(decisionFor("")).isEqualTo("ClarificationRequired");
    }

    @Test
    void concrete_status_codes_and_field_names_count_as_testable_signals() {
        assertThat(RequirementAgents.RequirementQualityAgent.hasTestableSignals(SCENARIO_A)).isTrue();
        assertThat(RequirementAgents.RequirementQualityAgent.hasTestableSignals(SCENARIO_C)).isFalse();
    }
}
