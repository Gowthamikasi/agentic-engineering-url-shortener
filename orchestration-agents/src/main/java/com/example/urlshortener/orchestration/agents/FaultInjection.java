package com.example.urlshortener.orchestration.agents;

import com.example.urlshortener.orchestration.engine.StageContext;
import com.example.urlshortener.orchestration.model.FailureClass;

import java.util.Map;
import java.util.Optional;

/**
 * Deterministic fault injection, declared in a run's input.
 *
 * <p>The reliability behaviour of this system — retry, rollback, compensation, MTTR — can only be
 * demonstrated if failures actually happen. Waiting for a real one would produce no evidence, and
 * writing the numbers by hand would produce false evidence. So failures are injected explicitly,
 * from the run input, and every injected failure carries "(fault-injected)" in its reason so it is
 * visible as such in the audit trail and in the metrics population.
 *
 * <p>Input shape:
 * <pre>
 * "faults": { "contract-tests": { "times": 1, "class": "TRANSIENT", "reason": "H2 lock timeout" } }
 * </pre>
 */
public final class FaultInjection {

    /** A fault an agent should raise for the current attempt. */
    public record Fault(FailureClass failureClass, String reason) {
    }

    public static final String MARKER = "(fault-injected)";

    private FaultInjection() {
    }

    /** @return the fault to raise on this attempt, or empty when the attempt should proceed normally */
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
