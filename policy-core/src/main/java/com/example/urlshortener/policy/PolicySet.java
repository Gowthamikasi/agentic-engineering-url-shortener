package com.example.urlshortener.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** A versioned set of guardrails. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicySet(String version, String description, List<PolicyRule> rules) {

    public PolicyRule rule(String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such policy rule: " + id));
    }
}
