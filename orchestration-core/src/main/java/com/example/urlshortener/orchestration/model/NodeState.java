package com.example.urlshortener.orchestration.model;

/** States a node can be in. */
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

    /** No more work will be dispatched for this node. */
    public boolean isSettled() {
        return this == SUCCEEDED || this == APPROVED || this == SKIPPED
                || this == FAILED || this == REJECTED || this == SAFE_STOPPED || this == BLOCKED;
    }

    /** Counts as a satisfied dependency downstream. */
    public boolean satisfiesDependency() {
        return this == SUCCEEDED || this == APPROVED || this == SKIPPED;
    }

    public boolean isActive() {
        return this == READY || this == RUNNING || this == RETRYING || this == AWAITING_APPROVAL
                || this == FALLING_BACK || this == ROLLING_BACK || this == COMPENSATING;
    }
}
