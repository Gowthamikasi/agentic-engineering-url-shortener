package com.example.urlshortener.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates a versioned policy set against one run.
 *
 * <p>A mandatory FAIL sets releaseBlocked. An EXCEPTION_REQUESTED only becomes a pass when a
 * matching exception is approved and still valid; a lapsed one is reported as a FAIL naming the
 * expiry, so nothing is waived just by time passing.
 */
public final class PolicyEvaluator {

    /** A single named check. Pure: facts in, verdict out. */
    @FunctionalInterface
    public interface PolicyCheck {
        CheckResult evaluate(PolicyContext context);
    }

    /** What a check decided, before exception handling is applied. */
    public record CheckResult(PolicyOutcome outcome, String reason, String evidence) {

        public static CheckResult pass(String evidence) {
            return new CheckResult(PolicyOutcome.PASS, "Satisfied.", evidence);
        }

        public static CheckResult fail(String reason) {
            return new CheckResult(PolicyOutcome.FAIL, reason, null);
        }

        public static CheckResult fail(String reason, String evidence) {
            return new CheckResult(PolicyOutcome.FAIL, reason, evidence);
        }

        public static CheckResult notApplicable(String reason) {
            return new CheckResult(PolicyOutcome.NOT_APPLICABLE, reason, null);
        }

        public static CheckResult exceptionRequested(String reason) {
            return new CheckResult(PolicyOutcome.EXCEPTION_REQUESTED, reason, null);
        }
    }

    /** The full verdict for one run against one policy version. */
    public record Result(String policyVersion, List<PolicyEvaluation> evaluations, boolean releaseBlocked,
                         Map<PolicyOutcome, Long> counts, List<String> blockingPolicyIds) {

        /** Compact form used in audit rows, e.g. {@code PASS:11 FAIL:0 EXC:0 NA:2}. */
        public String summary() {
            return "PASS:" + counts.getOrDefault(PolicyOutcome.PASS, 0L)
                    + " FAIL:" + counts.getOrDefault(PolicyOutcome.FAIL, 0L)
                    + " EXC:" + counts.getOrDefault(PolicyOutcome.EXCEPTION_REQUESTED, 0L)
                    + " NA:" + counts.getOrDefault(PolicyOutcome.NOT_APPLICABLE, 0L);
        }
    }

    private final Map<String, PolicyCheck> checks;

    public PolicyEvaluator(Map<String, PolicyCheck> checks) {
        this.checks = Map.copyOf(checks);
    }

    public Result evaluate(PolicySet policySet, PolicyContext context, Instant now) {
        List<PolicyEvaluation> evaluations = new ArrayList<>();
        List<String> blocking = new ArrayList<>();

        for (PolicyRule rule : policySet.rules()) {
            CheckResult raw = runCheck(rule, context);
            PolicyEvaluation evaluation = applyExceptions(rule, raw, context, now);
            evaluations.add(evaluation);
            if (rule.mandatory() && evaluation.outcome() == PolicyOutcome.FAIL) {
                blocking.add(rule.id());
            }
        }

        Map<PolicyOutcome, Long> counts = new LinkedHashMap<>();
        for (PolicyOutcome outcome : PolicyOutcome.values()) {
            counts.put(outcome, evaluations.stream().filter(e -> e.outcome() == outcome).count());
        }

        return new Result(policySet.version(), List.copyOf(evaluations), !blocking.isEmpty(),
                counts, List.copyOf(blocking));
    }

    private CheckResult runCheck(PolicyRule rule, PolicyContext context) {
        PolicyCheck check = checks.get(rule.check());
        if (check == null) {
            // An unimplemented check must not silently pass; it is a hard failure of the policy set itself.
            return CheckResult.fail("No check registered for '" + rule.check() + "'.");
        }
        try {
            return check.evaluate(context);
        } catch (RuntimeException e) {
            return CheckResult.fail("Check threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Turns EXCEPTION_REQUESTED into PASS only for a live approved exception; a lapsed one becomes FAIL. */
    private PolicyEvaluation applyExceptions(PolicyRule rule, CheckResult raw, PolicyContext context, Instant now) {
        if (raw.outcome() != PolicyOutcome.EXCEPTION_REQUESTED) {
            return new PolicyEvaluation(rule.id(), rule.domain(), rule.mandatory(),
                    raw.outcome(), raw.reason(), raw.evidence());
        }

        Optional<PolicyException> exception = context.exceptionFor(rule.id());
        if (exception.isEmpty()) {
            return new PolicyEvaluation(rule.id(), rule.domain(), rule.mandatory(),
                    PolicyOutcome.EXCEPTION_REQUESTED,
                    raw.reason() + " Awaiting a human decision on the exception request.", null);
        }

        PolicyException approved = exception.get();
        if (approved.isExpiredAt(now)) {
            return new PolicyEvaluation(rule.id(), rule.domain(), rule.mandatory(), PolicyOutcome.FAIL,
                    raw.reason() + " Exception " + approved.id() + " expired at " + approved.expiresAt() + ".",
                    approved.compensatingControl());
        }
        if (!approved.isActiveAt(now)) {
            return new PolicyEvaluation(rule.id(), rule.domain(), rule.mandatory(),
                    PolicyOutcome.EXCEPTION_REQUESTED,
                    raw.reason() + " Exception " + approved.id() + " is not approved.", null);
        }
        return new PolicyEvaluation(rule.id(), rule.domain(), rule.mandatory(), PolicyOutcome.PASS,
                "Waived by exception " + approved.id() + " approved by " + approved.approver() + ".",
                approved.compensatingControl());
    }
}
