package com.example.urlshortener.policy;

/**
 * The four possible outcomes (REQ-D-004).
 *
 * <p>There is no "warning" and no "skipped": a check that does not apply says so, which keeps the
 * counts meaningful.
 */
public enum PolicyOutcome {
    PASS,
    FAIL,
    EXCEPTION_REQUESTED,
    NOT_APPLICABLE
}
