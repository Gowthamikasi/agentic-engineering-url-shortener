package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.NodeState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The prohibited transitions.
 *
 * <p>These are the negative tests that give the governance claims their teeth. Each one asserts
 * that a specific shortcut — approving without a decision, retrying past the budget, resurrecting
 * a failed node — is simply not expressible.
 */
class NodeStateMachineTest {

    @Test
    void a_gate_cannot_jump_from_awaiting_approval_straight_to_succeeded() {
        assertThat(NodeStateMachine.isAllowed(NodeState.AWAITING_APPROVAL, NodeState.SUCCEEDED)).isFalse();

        assertThatThrownBy(() -> NodeStateMachine.requireAllowed(
                "release-gate", NodeState.AWAITING_APPROVAL, NodeState.SUCCEEDED))
                .isInstanceOf(NodeStateMachine.IllegalTransitionException.class)
                .hasMessageContaining("AWAITING_APPROVAL -> SUCCEEDED");
    }

    @Test
    void a_gate_reaches_succeeded_only_through_approved() {
        assertThat(NodeStateMachine.isAllowed(NodeState.AWAITING_APPROVAL, NodeState.APPROVED)).isTrue();
        assertThat(NodeStateMachine.isAllowed(NodeState.APPROVED, NodeState.SUCCEEDED)).isTrue();
    }

    @Test
    void a_failed_node_cannot_become_successful_without_going_through_recovery() {
        assertThat(NodeStateMachine.isAllowed(NodeState.FAILED, NodeState.SUCCEEDED)).isFalse();
        assertThat(NodeStateMachine.isAllowed(NodeState.FAILED, NodeState.FALLING_BACK)).isTrue();
        assertThat(NodeStateMachine.isAllowed(NodeState.FALLING_BACK, NodeState.SUCCEEDED)).isTrue();
    }

    @Test
    void rejection_is_final() {
        assertThat(NodeStateMachine.allowedFrom(NodeState.REJECTED)).isEmpty();

        for (NodeState target : NodeState.values()) {
            assertThat(NodeStateMachine.isAllowed(NodeState.REJECTED, target))
                    .as("REJECTED -> %s", target)
                    .isFalse();
        }
    }

    @Test
    void an_invalidated_node_must_be_re_executed_and_cannot_jump_back_to_succeeded() {
        assertThat(NodeStateMachine.isAllowed(NodeState.INVALIDATED, NodeState.SUCCEEDED)).isFalse();
        assertThat(NodeStateMachine.isAllowed(NodeState.INVALIDATED, NodeState.PENDING)).isTrue();
    }

    @Test
    void a_skipped_node_cannot_report_success_without_running() {
        assertThat(NodeStateMachine.isAllowed(NodeState.SKIPPED, NodeState.SUCCEEDED)).isFalse();
    }

    @Test
    void a_pending_node_cannot_start_running_without_becoming_ready_first() {
        assertThat(NodeStateMachine.isAllowed(NodeState.PENDING, NodeState.RUNNING)).isFalse();
        assertThat(NodeStateMachine.isAllowed(NodeState.PENDING, NodeState.READY)).isTrue();
        assertThat(NodeStateMachine.isAllowed(NodeState.READY, NodeState.RUNNING)).isTrue();
    }

    @Test
    void recovery_states_lead_only_to_their_completed_form() {
        assertThat(NodeStateMachine.allowedFrom(NodeState.ROLLING_BACK))
                .containsExactlyInAnyOrder(NodeState.ROLLED_BACK, NodeState.SAFE_STOPPED);
        assertThat(NodeStateMachine.allowedFrom(NodeState.COMPENSATING))
                .containsExactlyInAnyOrder(NodeState.COMPENSATED, NodeState.SAFE_STOPPED);
    }

    @Test
    void a_safe_stopped_node_can_only_be_restarted_by_a_human_resume() {
        assertThat(NodeStateMachine.allowedFrom(NodeState.SAFE_STOPPED))
                .containsExactlyInAnyOrder(NodeState.READY, NodeState.PENDING);
        assertThat(NodeStateMachine.isAllowed(NodeState.SAFE_STOPPED, NodeState.SUCCEEDED)).isFalse();
    }

    @Test
    void dependency_satisfaction_counts_skipped_but_not_failed() {
        assertThat(NodeState.SUCCEEDED.satisfiesDependency()).isTrue();
        assertThat(NodeState.APPROVED.satisfiesDependency()).isTrue();
        assertThat(NodeState.SKIPPED.satisfiesDependency()).isTrue();
        assertThat(NodeState.FAILED.satisfiesDependency()).isFalse();
        assertThat(NodeState.REJECTED.satisfiesDependency()).isFalse();
        assertThat(NodeState.SAFE_STOPPED.satisfiesDependency()).isFalse();
    }
}
