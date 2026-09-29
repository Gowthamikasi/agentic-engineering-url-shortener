package com.example.urlshortener.policy;

import com.example.urlshortener.policy.PolicyEvaluator.CheckResult;
import com.example.urlshortener.policy.PolicyEvaluator.PolicyCheck;
import com.example.urlshortener.policy.PolicyEvaluator.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T14:00:00Z");

    private static PolicySet setOf(PolicyRule... rules) {
        return new PolicySet("1.0.0", "test set", List.of(rules));
    }

    private static PolicyRule mandatory(String id, String check) {
        return new PolicyRule(id, PolicyDomain.SECURITY, id, true, check);
    }

    private static PolicyRule advisory(String id, String check) {
        return new PolicyRule(id, PolicyDomain.LICENSING, id, false, check);
    }

    private static PolicyEvaluator evaluatorWith(Map<String, PolicyCheck> checks) {
        return new PolicyEvaluator(checks);
    }

    @Test
    void a_mandatory_failure_blocks_release_and_names_the_rule() {
        PolicyEvaluator evaluator = evaluatorWith(Map.of(
                "ok", ctx -> CheckResult.pass("fine"),
                "bad", ctx -> CheckResult.fail("not fine")));

        Result result = evaluator.evaluate(setOf(mandatory("SEC-001", "ok"), mandatory("SEC-002", "bad")),
                PolicyContext.builder().build(), NOW);

        assertThat(result.releaseBlocked()).isTrue();
        assertThat(result.blockingPolicyIds()).containsExactly("SEC-002");
        assertThat(result.summary()).isEqualTo("PASS:1 FAIL:1 EXC:0 NA:0");
    }

    @Test
    void an_advisory_failure_is_recorded_but_does_not_block_release() {
        PolicyEvaluator evaluator = evaluatorWith(Map.of("bad", ctx -> CheckResult.fail("licence outside allowlist")));

        Result result = evaluator.evaluate(setOf(advisory("LIC-001", "bad")), PolicyContext.builder().build(), NOW);

        assertThat(result.releaseBlocked()).isFalse();
        assertThat(result.blockingPolicyIds()).isEmpty();
        assertThat(result.evaluations()).singleElement()
                .satisfies(e -> assertThat(e.outcome()).isEqualTo(PolicyOutcome.FAIL));
    }

    @Test
    void an_exception_request_without_a_decision_stays_pending_and_is_not_a_pass() {
        PolicyEvaluator evaluator = evaluatorWith(Map.of("scan", ctx -> CheckResult.exceptionRequested("no scan ran")));

        Result result = evaluator.evaluate(setOf(mandatory("SEC-004", "scan")), PolicyContext.builder().build(), NOW);

        assertThat(result.evaluations()).singleElement()
                .satisfies(e -> assertThat(e.outcome()).isEqualTo(PolicyOutcome.EXCEPTION_REQUESTED));
        assertThat(result.releaseBlocked()).isFalse();
    }

    @Test
    void an_approved_unexpired_exception_turns_the_request_into_a_pass_and_records_the_compensating_control() {
        PolicyEvaluator evaluator = evaluatorWith(Map.of("scan", ctx -> CheckResult.exceptionRequested("no scan ran")));
        PolicyException approved = new PolicyException("exc-1", "SEC-004", "scanner unavailable offline",
                "this run only", "madhu", "manual dependency review recorded in ADR-018",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null);

        Result result = evaluator.evaluate(setOf(mandatory("SEC-004", "scan")),
                PolicyContext.builder().exception(approved).build(), NOW);

        PolicyEvaluation evaluation = result.evaluations().get(0);
        assertThat(evaluation.outcome()).isEqualTo(PolicyOutcome.PASS);
        assertThat(evaluation.reason()).contains("exc-1").contains("madhu");
        assertThat(evaluation.evidence()).contains("manual dependency review");
        assertThat(result.releaseBlocked()).isFalse();
    }

    @Test
    void an_expired_exception_is_re_evaluated_as_a_failure_and_blocks_release() {
        PolicyEvaluator evaluator = evaluatorWith(Map.of("scan", ctx -> CheckResult.exceptionRequested("no scan ran")));
        PolicyException lapsed = new PolicyException("exc-1", "SEC-004", "scanner unavailable offline",
                "this run only", "madhu", "manual dependency review",
                NOW.minusSeconds(7200), NOW.minusSeconds(60), null);

        Result result = evaluator.evaluate(setOf(mandatory("SEC-004", "scan")),
                PolicyContext.builder().exception(lapsed).build(), NOW);

        assertThat(result.evaluations().get(0).outcome()).isEqualTo(PolicyOutcome.FAIL);
        assertThat(result.evaluations().get(0).reason()).contains("expired");
        assertThat(result.releaseBlocked()).isTrue();
    }

    @Test
    void a_rule_whose_check_is_not_registered_fails_rather_than_passing_silently() {
        PolicyEvaluator evaluator = evaluatorWith(Map.of());

        Result result = evaluator.evaluate(setOf(mandatory("SEC-009", "check.that.does.not.exist")),
                PolicyContext.builder().build(), NOW);

        assertThat(result.evaluations().get(0).outcome()).isEqualTo(PolicyOutcome.FAIL);
        assertThat(result.releaseBlocked()).isTrue();
    }

    @Test
    void a_check_that_throws_is_reported_as_a_failure_not_an_engine_crash() {
        PolicyEvaluator evaluator = evaluatorWith(Map.of("boom", ctx -> {
            throw new IllegalStateException("kaboom");
        }));

        Result result = evaluator.evaluate(setOf(mandatory("SEC-010", "boom")), PolicyContext.builder().build(), NOW);

        assertThat(result.evaluations().get(0).outcome()).isEqualTo(PolicyOutcome.FAIL);
        assertThat(result.evaluations().get(0).reason()).contains("kaboom");
    }

    @Test
    void not_applicable_is_counted_separately_from_pass() {
        PolicyEvaluator evaluator = evaluatorWith(Map.of("na", ctx -> CheckResult.notApplicable("does not apply")));

        Result result = evaluator.evaluate(setOf(mandatory("CC-003", "na")), PolicyContext.builder().build(), NOW);

        assertThat(result.summary()).isEqualTo("PASS:0 FAIL:0 EXC:0 NA:1");
        assertThat(result.releaseBlocked()).isFalse();
    }
}
