package com.example.urlshortener.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One rule in a versioned policy set.
 *
 * @param id          stable identifier quoted in evaluations and exceptions
 * @param domain      guardrail family
 * @param description human-readable statement of what must hold
 * @param mandatory   REQ-D-005: a mandatory FAIL blocks downstream progression and release
 * @param check       key of the registered check that decides this rule
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyRule(String id, PolicyDomain domain, String description, boolean mandatory, String check) {
}
