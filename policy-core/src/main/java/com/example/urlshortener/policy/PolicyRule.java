package com.example.urlshortener.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One rule in a policy set.
 *
 * @param id          identifier quoted in evaluations and exceptions
 * @param domain      guardrail family
 * @param description what must hold
 * @param mandatory   a mandatory FAIL blocks the release (REQ-D-005)
 * @param check       key of the registered check that decides it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyRule(String id, PolicyDomain domain, String description, boolean mandatory, String check) {
}
