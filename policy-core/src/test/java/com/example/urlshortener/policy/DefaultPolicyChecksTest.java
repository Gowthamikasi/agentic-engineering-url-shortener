package com.example.urlshortener.policy;

import com.example.urlshortener.policy.PolicyEvaluator.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the shipped policy set end to end against realistic run facts. */
class DefaultPolicyChecksTest {

    private static final Instant NOW = Instant.parse("2026-09-28T14:00:00Z");

    private final PolicySet shipped = new PolicySetLoader().loadVersion("1.0.0");
    private final PolicyEvaluator evaluator = new PolicyEvaluator(DefaultPolicyChecks.registry());

    private static Map<String, Object> cleanGreenfieldRun() {
        return Map.ofEntries(
                Map.entry(DefaultPolicyChecks.F_SCHEME_ALLOWLIST, true),
                Map.entry(DefaultPolicyChecks.F_PRIVATE_HOSTS_BLOCKED, true),
                Map.entry(DefaultPolicyChecks.F_SECRET_MASKING, true),
                Map.entry(DefaultPolicyChecks.F_DEP_SCAN_RAN, true),
                Map.entry(DefaultPolicyChecks.F_DEP_SCAN_HIGH, 0),
                Map.entry(DefaultPolicyChecks.F_AUDIT_RETENTION, true),
                Map.entry(DefaultPolicyChecks.F_CLICK_PII_FIELDS, 0),
                Map.entry(DefaultPolicyChecks.F_LICENSE_REPORT, true),
                Map.entry(DefaultPolicyChecks.F_LICENSE_DISALLOWED, 0),
                Map.entry(DefaultPolicyChecks.F_CONTRACT_CHANGED, true),
                Map.entry(DefaultPolicyChecks.F_CONTRACT_VERSION_BUMPED, true),
                Map.entry(DefaultPolicyChecks.F_CONTRACT_APPROVED, true),
                Map.entry(DefaultPolicyChecks.F_ARCHITECTURE_CHANGED, false),
                Map.entry(DefaultPolicyChecks.F_INPUT_KIND, "Greenfield"),
                Map.entry(DefaultPolicyChecks.F_TDD_REQUIRED, true),
                Map.entry(DefaultPolicyChecks.F_TDD_EVIDENCE, true),
                Map.entry(DefaultPolicyChecks.F_TESTS_RUN, 61),
                Map.entry(DefaultPolicyChecks.F_TESTS_FAILED, 0),
                Map.entry(DefaultPolicyChecks.F_CONTRACT_TESTS_RUN, 18));
    }

    @Test
    void the_shipped_policy_set_loads_with_every_check_registered() {
        Map<String, PolicyEvaluator.PolicyCheck> registry = DefaultPolicyChecks.registry();

        assertThat(shipped.version()).isEqualTo("1.0.0");
        assertThat(shipped.rules()).hasSize(13);
        assertThat(shipped.rules()).allSatisfy(rule ->
                assertThat(registry).containsKey(rule.check()));
    }

    @Test
    void a_clean_greenfield_run_passes_every_mandatory_rule() {
        Result result = evaluator.evaluate(shipped,
                PolicyContext.builder().facts(cleanGreenfieldRun()).build(), NOW);

        assertThat(result.releaseBlocked()).isFalse();
        assertThat(result.summary()).isEqualTo("PASS:11 FAIL:0 EXC:0 NA:2");
    }

    @Test
    void a_brownfield_run_without_impact_analysis_is_blocked() {
        Result result = evaluator.evaluate(shipped, PolicyContext.builder()
                .facts(cleanGreenfieldRun())
                .fact(DefaultPolicyChecks.F_INPUT_KIND, "Brownfield")
                .build(), NOW);

        assertThat(result.releaseBlocked()).isTrue();
        assertThat(result.blockingPolicyIds()).contains("CC-003");
    }

    @Test
    void an_architecture_change_without_an_adr_is_blocked() {
        Result result = evaluator.evaluate(shipped, PolicyContext.builder()
                .facts(cleanGreenfieldRun())
                .fact(DefaultPolicyChecks.F_ARCHITECTURE_CHANGED, true)
                .build(), NOW);

        assertThat(result.releaseBlocked()).isTrue();
        assertThat(result.blockingPolicyIds()).contains("CC-002");
    }

    @Test
    void an_architecture_change_with_an_adr_passes() {
        Result result = evaluator.evaluate(shipped, PolicyContext.builder()
                .facts(cleanGreenfieldRun())
                .fact(DefaultPolicyChecks.F_ARCHITECTURE_CHANGED, true)
                .fact(DefaultPolicyChecks.F_ADR_IDS, "ADR-017")
                .build(), NOW);

        assertThat(result.releaseBlocked()).isFalse();
    }

    @Test
    void a_skipped_dependency_scan_raises_an_exception_request_rather_than_passing() {
        Result result = evaluator.evaluate(shipped, PolicyContext.builder()
                .facts(cleanGreenfieldRun())
                .fact(DefaultPolicyChecks.F_DEP_SCAN_RAN, false)
                .build(), NOW);

        assertThat(result.evaluations()).anySatisfy(e -> {
            assertThat(e.policyId()).isEqualTo("SEC-004");
            assertThat(e.outcome()).isEqualTo(PolicyOutcome.EXCEPTION_REQUESTED);
        });
    }

    @Test
    void a_run_that_captured_no_tdd_evidence_raises_an_exception_request_rather_than_failing() {
        Map<String, Object> withoutEvidence = new java.util.LinkedHashMap<>(cleanGreenfieldRun());
        withoutEvidence.remove(DefaultPolicyChecks.F_TDD_EVIDENCE);

        Result result = evaluator.evaluate(shipped,
                PolicyContext.builder().facts(withoutEvidence).build(), NOW);

        assertThat(result.evaluations()).anySatisfy(e -> {
            assertThat(e.policyId()).isEqualTo("TEST-001");
            assertThat(e.outcome()).isEqualTo(PolicyOutcome.EXCEPTION_REQUESTED);
        });
        assertThat(result.releaseBlocked()).isFalse();
    }

    @Test
    void captured_evidence_that_shows_no_failing_run_first_does_block_release() {
        Result result = evaluator.evaluate(shipped, PolicyContext.builder()
                .facts(cleanGreenfieldRun())
                .fact(DefaultPolicyChecks.F_TDD_EVIDENCE, false)
                .build(), NOW);

        assertThat(result.releaseBlocked()).isTrue();
        assertThat(result.blockingPolicyIds()).contains("TEST-001");
    }

    @Test
    void failing_tests_block_release() {
        Result result = evaluator.evaluate(shipped, PolicyContext.builder()
                .facts(cleanGreenfieldRun())
                .fact(DefaultPolicyChecks.F_TESTS_FAILED, 3)
                .build(), NOW);

        assertThat(result.releaseBlocked()).isTrue();
        assertThat(result.blockingPolicyIds()).contains("TEST-002");
    }

    @Test
    void a_contract_change_without_a_version_bump_blocks_release() {
        Result result = evaluator.evaluate(shipped, PolicyContext.builder()
                .facts(cleanGreenfieldRun())
                .fact(DefaultPolicyChecks.F_CONTRACT_VERSION_BUMPED, false)
                .build(), NOW);

        assertThat(result.releaseBlocked()).isTrue();
        assertThat(result.blockingPolicyIds()).contains("CC-001");
    }
}
