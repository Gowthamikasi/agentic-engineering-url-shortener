package com.example.urlshortener.orchestration.model;

/**
 * States of a whole run. {@code SUSPENDED} and {@code AWAITING_CLARIFICATION} are resumable;
 * the rest are terminal, and which terminal state a run lands in is deterministic given its
 * journal.
 */
public enum InstanceState {

    CREATED,
    RUNNING,
    SUSPENDED,
    AWAITING_CLARIFICATION,
    COMPLETED,
    COMPLETED_WITH_LIMITATIONS,
    SAFE_STOPPED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPLETED_WITH_LIMITATIONS
                || this == SAFE_STOPPED || this == REJECTED || this == FAILED;
    }

    public boolean isResumable() {
        return this == SUSPENDED || this == AWAITING_CLARIFICATION;
    }
}
