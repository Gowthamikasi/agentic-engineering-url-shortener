package com.example.urlshortener.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The facts a policy check may look at: artifacts the run produced, flags the engine set,
 * and the exceptions already approved for this run.
 *
 * <p>Checks read facts and nothing else. They have no database, no file system and no clock
 * of their own, which is what makes a policy verdict reproducible from the journal alone.
 */
public final class PolicyContext {

    private final Map<String, Object> facts;
    private final List<PolicyException> exceptions;

    private PolicyContext(Map<String, Object> facts, List<PolicyException> exceptions) {
        this.facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        this.exceptions = List.copyOf(exceptions);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Object> facts() {
        return facts;
    }

    public List<PolicyException> exceptions() {
        return exceptions;
    }

    public Optional<Object> fact(String key) {
        return Optional.ofNullable(facts.get(key));
    }

    public boolean flag(String key) {
        Object value = facts.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    public String text(String key) {
        Object value = facts.get(key);
        return value == null ? null : value.toString();
    }

    public long number(String key, long fallback) {
        Object value = facts.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** True when the key is absent entirely, which lets a check answer NOT_APPLICABLE honestly. */
    public boolean missing(String key) {
        return !facts.containsKey(key);
    }

    public Optional<PolicyException> exceptionFor(String policyId) {
        return exceptions.stream().filter(e -> policyId.equals(e.policyId())).findFirst();
    }

    public static final class Builder {

        private final Map<String, Object> facts = new LinkedHashMap<>();
        private final List<PolicyException> exceptions = new java.util.ArrayList<>();

        public Builder fact(String key, Object value) {
            facts.put(key, value);
            return this;
        }

        public Builder facts(Map<String, Object> more) {
            facts.putAll(more);
            return this;
        }

        public Builder exception(PolicyException exception) {
            exceptions.add(exception);
            return this;
        }

        public Builder exceptions(List<PolicyException> more) {
            exceptions.addAll(more);
            return this;
        }

        public PolicyContext build() {
            return new PolicyContext(facts, exceptions);
        }
    }
}
