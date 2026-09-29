package com.example.urlshortener.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A versioned set of guardrails. Every run stamps the version it was evaluated against
 * (REQ-D-003), so a later policy change never silently reinterprets an old run's verdict.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicySet(String version, String description, List<PolicyRule> rules) {

    public PolicyRule rule(String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such policy rule: " + id));
    }
}
