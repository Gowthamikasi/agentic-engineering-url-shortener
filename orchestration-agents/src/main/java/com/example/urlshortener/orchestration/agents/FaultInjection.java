package com.example.urlshortener.orchestration.agents;

import com.example.urlshortener.orchestration.engine.StageContext;
import com.example.urlshortener.orchestration.model.FailureClass;

import java.util.Map;
import java.util.Optional;

/**
 * Deterministic fault injection, declared in a run's input.
 *
 * <p>Retry, rollback and MTTR can only be demonstrated if something actually fails, so
 * failures are injected on purpose. Every one carries "(fault-injected)" in its reason so
 * it is obvious in the audit trail.
 *
 * <p>Input shape:
 * <pre>
 * "faults": { "contract-tests": { "times": 1, "class": "TRANSIENT", "reason": "lock timeout" } }
 * </pre>
 */
public final class FaultInjection {

    /** A fault an agent should raise on the current attempt. */
    public record Fault(FailureClass failureClass, String reason) {
    }

    public static final String MARKER = "(fault-injected)";

    private FaultInjection() {
    }

    /** @return the fault for this attempt, or empty if the attempt should run normally */
    @SuppressWarnings("unchecked")
    public static Optional<Fault> forAttempt(StageContext context) {
        Object faults = context.input().get("faults");
        if (!(faults instanceof Map<?, ?> byNode)) {
            return Optional.empty();
        }
        Object spec = byNode.get(context.nodeId());
        if (!(spec instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        Map<String, Object> fault = (Map<String, Object>) raw;

        int times = asInt(fault.get("times"), 1);
        if (context.attempt() > times) {
            return Optional.empty();
        }

        FailureClass failureClass = FailureClass.valueOf(
                String.valueOf(fault.getOrDefault("class", "TRANSIENT")).toUpperCase(java.util.Locale.ROOT));
        String reason = String.valueOf(fault.getOrDefault("reason", "Injected failure"));

        return Optional.of(new Fault(failureClass, reason + " " + MARKER));
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
