package com.example.urlshortener.policy;

/** The verdict for one rule in one run. */
public record PolicyEvaluation(String policyId, PolicyDomain domain, boolean mandatory,
                               PolicyOutcome outcome, String reason, String evidence) {
}
