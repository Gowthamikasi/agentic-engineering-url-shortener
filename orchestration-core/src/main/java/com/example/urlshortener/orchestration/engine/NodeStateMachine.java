package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.NodeState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The one place node transitions are allowed or refused.
 *
 * <p>The table is closed: anything not listed throws. Several governance rules are enforced by a
 * missing edge rather than by a check somewhere else, so changing one means editing this table
 * where the tests will notice.
 */
public final class NodeStateMachine {

    /** Thrown when code attempts a transition the table does not permit. */
    public static class IllegalTransitionException extends RuntimeException {

        public IllegalTransitionException(String nodeId, NodeState from, NodeState to) {
            super("Illegal node transition for '" + nodeId + "': " + from + " -> " + to);
        }
    }

    private static final Map<NodeState, Set<NodeState>> ALLOWED = buildTable();

    private NodeStateMachine() {
    }

    private static Map<NodeState, Set<NodeState>> buildTable() {
        Map<NodeState, Set<NodeState>> table = new EnumMap<>(NodeState.class);

        table.put(NodeState.PENDING, EnumSet.of(
                NodeState.READY, NodeState.BLOCKED, NodeState.SKIPPED, NodeState.SAFE_STOPPED));
        table.put(NodeState.READY, EnumSet.of(
                NodeState.RUNNING, NodeState.AWAITING_APPROVAL, NodeState.BLOCKED,
                NodeState.SKIPPED, NodeState.SAFE_STOPPED));
        table.put(NodeState.RUNNING, EnumSet.of(
                NodeState.SUCCEEDED, NodeState.FAILED, NodeState.RETRYING, NodeState.TIMED_OUT,
                NodeState.AWAITING_APPROVAL, NodeState.SAFE_STOPPED));
        table.put(NodeState.RETRYING, EnumSet.of(
                NodeState.READY, NodeState.FAILED, NodeState.SAFE_STOPPED));
        table.put(NodeState.TIMED_OUT, EnumSet.of(
                NodeState.RETRYING, NodeState.FAILED, NodeState.SAFE_STOPPED));

        // A gate leaves AWAITING_APPROVAL only by a recorded decision or a safe stop. There is
        // deliberately no edge to SUCCEEDED here: approval must go through APPROVED, which the
        // engine only sets once an ApprovalDecision exists.
        table.put(NodeState.AWAITING_APPROVAL, EnumSet.of(
                NodeState.APPROVED, NodeState.REJECTED, NodeState.SAFE_STOPPED));
        table.put(NodeState.APPROVED, EnumSet.of(NodeState.SUCCEEDED, NodeState.INVALIDATED));

        table.put(NodeState.FAILED, EnumSet.of(
                NodeState.FALLING_BACK, NodeState.ROLLING_BACK, NodeState.COMPENSATING,
                NodeState.SAFE_STOPPED, NodeState.RETRYING));
        table.put(NodeState.FALLING_BACK, EnumSet.of(
                NodeState.SUCCEEDED, NodeState.COMPENSATING, NodeState.FAILED, NodeState.SAFE_STOPPED));
        table.put(NodeState.ROLLING_BACK, EnumSet.of(NodeState.ROLLED_BACK, NodeState.SAFE_STOPPED));
        table.put(NodeState.COMPENSATING, EnumSet.of(NodeState.COMPENSATED, NodeState.SAFE_STOPPED));
        table.put(NodeState.ROLLED_BACK, EnumSet.of(NodeState.READY, NodeState.SAFE_STOPPED, NodeState.PENDING));
        table.put(NodeState.COMPENSATED, EnumSet.of(NodeState.READY, NodeState.SAFE_STOPPED, NodeState.PENDING));

        table.put(NodeState.SAFE_STOPPED, EnumSet.of(NodeState.READY, NodeState.PENDING));
        table.put(NodeState.BLOCKED, EnumSet.of(NodeState.PENDING, NodeState.SAFE_STOPPED, NodeState.SKIPPED));
        table.put(NodeState.SUCCEEDED, EnumSet.of(NodeState.INVALIDATED));
        table.put(NodeState.SKIPPED, EnumSet.of(NodeState.PENDING, NodeState.INVALIDATED));
        table.put(NodeState.INVALIDATED, EnumSet.of(NodeState.PENDING));

        // Rejection is final. A rejected gate is re-run only as a new workflow instance.
        table.put(NodeState.REJECTED, EnumSet.noneOf(NodeState.class));

        return Map.copyOf(table);
    }

    public static boolean isAllowed(NodeState from, NodeState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** @throws IllegalTransitionException when the transition is not in the table */
    public static void requireAllowed(String nodeId, NodeState from, NodeState to) {
        if (!isAllowed(from, to)) {
            throw new IllegalTransitionException(nodeId, from, to);
        }
    }

    public static Set<NodeState> allowedFrom(NodeState from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
