package com.example.urlshortener.telemetry.audit;

/**
 * The closed vocabulary of audited actions. Keeping these as constants (rather than free text)
 * is what lets the reliability calculator derive MTTR from the journal instead of hand-entry.
 */
public final class AuditActions {

    public static final String WORKFLOW_CREATED = "WorkflowCreated";
    public static final String WORKFLOW_COMPLETED = "WorkflowCompleted";
    public static final String WORKFLOW_COMPLETED_WITH_LIMITATIONS = "WorkflowCompletedWithLimitations";
    public static final String WORKFLOW_FAILED = "WorkflowFailed";
    public static final String WORKFLOW_SUSPENDED = "WorkflowSuspended";
    public static final String WORKFLOW_REJECTED = "WorkflowRejected";
    public static final String WORKFLOW_RESUMED = "WorkflowResumed";
    public static final String WORKFLOW_AWAITING_CLARIFICATION = "WorkflowAwaitingClarification";

    public static final String NODE_READY = "NodeReady";
    public static final String NODE_STARTED = "NodeStarted";
    public static final String NODE_SUCCEEDED = "NodeSucceeded";
    public static final String NODE_FAILED = "NodeFailed";
    public static final String NODE_TIMED_OUT = "NodeTimedOut";
    public static final String NODE_SKIPPED = "NodeSkipped";
    public static final String NODE_BLOCKED = "NodeBlocked";
    public static final String NODE_RETRY_SCHEDULED = "NodeRetryScheduled";
    /** A node claimed success without producing the output its definition declares. */
    public static final String EXIT_GATE_FAILED = "ExitGateFailed";

    public static final String APPROVAL_REQUESTED = "ApprovalRequested";
    public static final String APPROVAL_DECIDED = "ApprovalDecided";
    /** The node state change that follows a decision, kept distinct so the trail has one row per fact. */
    public static final String APPROVAL_APPLIED = "ApprovalApplied";

    public static final String FALLBACK_APPLIED = "FallbackApplied";
    public static final String ROLLBACK_STARTED = "RollbackStarted";
    public static final String ROLLBACK_COMPLETED = "RollbackCompleted";
    public static final String COMPENSATION_STARTED = "CompensationStarted";
    public static final String COMPENSATION_COMPLETED = "CompensationCompleted";
    public static final String SAFE_STOP = "SafeStop";

    public static final String POLICY_EVALUATED = "PolicyEvaluated";
    public static final String POLICY_EXCEPTION_REQUESTED = "PolicyExceptionRequested";
    public static final String POLICY_EXCEPTION_DECIDED = "PolicyExceptionDecided";

    public static final String INVALIDATED = "Invalidated";
    public static final String REPLANNED = "Replanned";

    private AuditActions() {
    }
}
