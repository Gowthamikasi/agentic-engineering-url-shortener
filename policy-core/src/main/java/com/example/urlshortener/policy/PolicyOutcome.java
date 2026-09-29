package com.example.urlshortener.policy;

/**
 * REQ-D-004: the outcome vocabulary is closed. There is no "warning" and no "skipped" —
 * a check that does not apply to this run says so explicitly, which keeps the evaluation
 * summary countable and stops advisory findings from quietly disappearing.
 */
public enum PolicyOutcome {
    PASS,
    FAIL,
    EXCEPTION_REQUESTED,
    NOT_APPLICABLE
}
