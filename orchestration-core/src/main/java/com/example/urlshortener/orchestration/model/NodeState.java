package com.example.urlshortener.orchestration.model;

/**
 * States a workflow node can occupy. Legal movement between them is defined once, in
 * {@code NodeStateMachine}, and every transition in the system goes through that table.
 */
public enum NodeState {

    PENDING,
    BLOCKED,
    READY,
    RUNNING,
    RETRYING,
    TIMED_OUT,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    FALLING_BACK,
    ROLLING_BACK,
    ROLLED_BACK,
    COMPENSATING,
    COMPENSATED,
    SAFE_STOPPED,
    SKIPPED,
    INVALIDATED,
    SUCCEEDED,
    FAILED;

    /** Terminal for the purposes of the ready-set computation: no further work will be dispatched. */
    public boolean isSettled() {
        return this == SUCCEEDED || this == APPROVED || this == SKIPPED
                || this == FAILED || this == REJECTED || this == SAFE_STOPPED || this == BLOCKED;
    }

    /** Counts as a satisfied dependency for a downstream node. */
    public boolean satisfiesDependency() {
        return this == SUCCEEDED || this == APPROVED || this == SKIPPED;
    }

    public boolean isActive() {
        return this == READY || this == RUNNING || this == RETRYING || this == AWAITING_APPROVAL
                || this == FALLING_BACK || this == ROLLING_BACK || this == COMPENSATING;
    }
}
