package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Criticality;
import com.example.urlshortener.orchestration.model.FailureClass;
import com.example.urlshortener.orchestration.model.InstanceState;
import com.example.urlshortener.orchestration.model.JoinType;
import com.example.urlshortener.orchestration.model.NodeDefinition;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.RecoveryMode;
import com.example.urlshortener.orchestration.model.TransitionEvent;
import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import com.example.urlshortener.orchestration.model.WorkflowInstance;
import com.example.urlshortener.telemetry.audit.AuditActions;
import com.example.urlshortener.telemetry.audit.AuditEvent;
import com.example.urlshortener.telemetry.audit.AuditHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for the engine: what actually happens when nodes run in parallel, fail,
 * time out, get rejected, or have their inputs changed underneath them.
 */
class WorkflowEngineTest {

    private InMemoryStores.MemoryJournal journal;
    private InMemoryStores.MemoryApprovalStore approvals;
    private InMemoryStores.MemoryInstanceStore instances;
    private InMemoryStores.MemoryAuditSink audit;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        journal = new InMemoryStores.MemoryJournal();
        approvals = new InMemoryStores.MemoryApprovalStore();
        instances = new InMemoryStores.MemoryInstanceStore();
        audit = new InMemoryStores.MemoryAuditSink();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private WorkflowEngine engineWith(List<StageAgent> agents) {
        return engineWith(agents, EngineSettings.defaults());
    }

    private WorkflowEngine engineWith(List<StageAgent> agents, EngineSettings settings) {
        engine = new WorkflowEngine(agents, journal, approvals, instances, audit, Clock.systemUTC(), settings);
        return engine;
    }

    // ---------------------------------------------------------------- definitions

    private static NodeDefinition node(String id, String agentType, List<String> dependsOn) {
        return new NodeDefinition(id, agentType, dependsOn, JoinType.ALL, false, null,
                5_000, 1, 50, RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, null, id);
    }

    private static NodeDefinition gate(String id, List<String> dependsOn, String supersedes) {
        return new NodeDefinition(id, "HumanGateAgent", dependsOn, JoinType.ALL, true, id,
                5_000, 1, 50, RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, supersedes, id);
    }

    private static WorkflowDefinition definition(NodeDefinition... nodes) {
        return new WorkflowDefinition("test", 1, "test", List.of(nodes));
    }

    private static Map<String, Object> input() {
        return Map.of("text", "a requirement", "actor", "madhu", "kind", "Greenfield");
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void a_linear_run_completes_and_journals_every_node() {
        TestAgents.RecordingAgent agent = new TestAgents.RecordingAgent("TestAgent");
        WorkflowEngine engine = engineWith(List.of(agent));

        WorkflowInstance instance = engine.start(
                definition(node("a", "TestAgent", List.of()), node("b", "TestAgent", List.of("a"))),
                input(), "1.0.0");
        assertThat(engine.awaitQuiescence(instance.runId(), 10_000)).isTrue();

        assertThat(instance.state()).isEqualTo(InstanceState.COMPLETED);
        assertThat(agent.invocationsFor("a")).isEqualTo(1);
        assertThat(agent.invocationsFor("b")).isEqualTo(1);
        assertThat(journal.actionsFor(instance.runId(), "a"))
                .containsSubsequence(AuditActions.NODE_READY, AuditActions.NODE_STARTED, AuditActions.NODE_SUCCEEDED);
    }

    @Test
    void the_audit_chain_for_a_completed_run_verifies() {
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent")));

        WorkflowInstance instance = engine.start(
                definition(node("a", "TestAgent", List.of()), node("b", "TestAgent", List.of("a"))),
                input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        List<AuditEvent> events = audit.findByRun(instance.runId());
        assertThat(events).isNotEmpty();
        assertThat(AuditHasher.verifyChain(events)).isEqualTo(-1);
        assertThat(events).allSatisfy(e -> assertThat(e.policyVersion()).isEqualTo("1.0.0"));
    }

    // ---------------------------------------------------------------- parallelism and join

    @Test
    void sibling_nodes_run_concurrently_and_their_execution_windows_overlap() {
        TestAgents.OverlapProbeAgent probe = new TestAgents.OverlapProbeAgent("SlowWork", 300);
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent"), probe));

        WorkflowInstance instance = engine.start(definition(
                node("root", "TestAgent", List.of()),
                node("left", "SlowWork", List.of("root")),
                node("right", "SlowWork", List.of("root")),
                node("join", "TestAgent", List.of("left", "right"))), input(), "1.0.0");
        assertThat(engine.awaitQuiescence(instance.runId(), 20_000)).isTrue();

        TestAgents.OverlapProbeAgent.Window left = probe.windowFor("left");
        TestAgents.OverlapProbeAgent.Window right = probe.windowFor("right");

        // Real overlap: each started before the other finished.
        assertThat(left.start()).isBefore(right.end());
        assertThat(right.start()).isBefore(left.end());
        assertThat(instance.state()).isEqualTo(InstanceState.COMPLETED);
    }

    @Test
    void a_join_node_does_not_start_until_every_dependency_has_finished() {
        TestAgents.OverlapProbeAgent probe = new TestAgents.OverlapProbeAgent("SlowWork", 200);
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent"), probe));

        WorkflowInstance instance = engine.start(definition(
                node("root", "TestAgent", List.of()),
                node("left", "SlowWork", List.of("root")),
                node("right", "SlowWork", List.of("root")),
                node("join", "TestAgent", List.of("left", "right"))), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 20_000);

        TransitionEvent joinStarted = journal.forNode(instance.runId(), "join").stream()
                .filter(e -> AuditActions.NODE_STARTED.equals(e.action()))
                .findFirst().orElseThrow();

        for (String sibling : List.of("left", "right")) {
            TransitionEvent siblingSucceeded = journal.forNode(instance.runId(), sibling).stream()
                    .filter(e -> AuditActions.NODE_SUCCEEDED.equals(e.action()))
                    .findFirst().orElseThrow();
            assertThat(siblingSucceeded.timestamp())
                    .as("join must start after %s finished", sibling)
                    .isBeforeOrEqualTo(joinStarted.timestamp());
        }
    }

    @Test
    void a_pending_node_reports_which_dependencies_it_is_still_waiting_on() {
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent"),
                new TestAgents.SlowAgent("SlowWork", 400)));

        WorkflowDefinition definition = definition(
                node("root", "TestAgent", List.of()),
                node("slow", "SlowWork", List.of("root")),
                node("join", "TestAgent", List.of("slow")));

        WorkflowInstance instance = engine.start(definition, input(), "1.0.0");

        // While 'slow' is still working, 'join' has not been dispatched.
        assertThat(instance.node("join").state()).isIn(NodeState.PENDING, NodeState.READY, NodeState.RUNNING);
        engine.awaitQuiescence(instance.runId(), 20_000);
        assertThat(instance.node("join").state()).isEqualTo(NodeState.SUCCEEDED);
    }

    // ---------------------------------------------------------------- branching

    @Test
    void a_node_whose_branch_condition_is_false_is_skipped_with_a_stated_reason() {
        NodeDefinition conditional = new NodeDefinition("brownfield-only", "TestAgent", List.of("root"),
                JoinType.ALL, false, null, 5_000, 1, 50, RecoveryMode.NONE, Criticality.BLOCKING,
                "#input['kind'] == 'Brownfield'", false, false, null, null);

        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent")));
        WorkflowInstance instance = engine.start(
                definition(node("root", "TestAgent", List.of()), conditional,
                        node("after", "TestAgent", List.of("brownfield-only"))),
                input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        assertThat(instance.node("brownfield-only").state()).isEqualTo(NodeState.SKIPPED);
        assertThat(instance.node("brownfield-only").reason()).contains("branchCondition false");
        // A skipped node still satisfies its dependents, so the run does not stall.
        assertThat(instance.node("after").state()).isEqualTo(NodeState.SUCCEEDED);
        assertThat(instance.state()).isEqualTo(InstanceState.COMPLETED);
    }

    @Test
    void a_branch_condition_that_holds_lets_the_node_run() {
        NodeDefinition conditional = new NodeDefinition("brownfield-only", "TestAgent", List.of("root"),
                JoinType.ALL, false, null, 5_000, 1, 50, RecoveryMode.NONE, Criticality.BLOCKING,
                "#input['kind'] == 'Brownfield'", false, false, null, null);

        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent")));
        WorkflowInstance instance = engine.start(
                definition(node("root", "TestAgent", List.of()), conditional),
                Map.of("text", "x", "actor", "madhu", "kind", "Brownfield"), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        assertThat(instance.node("brownfield-only").state()).isEqualTo(NodeState.SUCCEEDED);
    }

    // ---------------------------------------------------------------- retry, timeout, recovery

    @Test
    void a_transient_failure_is_retried_within_the_budget_and_then_succeeds() {
        NodeDefinition flaky = new NodeDefinition("flaky", "Flaky", List.of(), JoinType.ALL, false, null,
                5_000, 3, 50, RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, null, null);

        WorkflowEngine engine = engineWith(List.of(new TestAgents.FlakyAgent("Flaky", 1, FailureClass.TRANSIENT)));
        WorkflowInstance instance = engine.start(definition(flaky), input(), "1.0.0");
        assertThat(engine.awaitQuiescence(instance.runId(), 20_000)).isTrue();

        assertThat(instance.node("flaky").state()).isEqualTo(NodeState.SUCCEEDED);
        assertThat(instance.node("flaky").attempts()).isEqualTo(2);
        assertThat(journal.actionsFor(instance.runId(), "flaky"))
                .contains(AuditActions.NODE_FAILED, AuditActions.NODE_RETRY_SCHEDULED, AuditActions.NODE_SUCCEEDED);
        assertThat(instance.state()).isEqualTo(InstanceState.COMPLETED);
    }

    @Test
    void the_retry_budget_is_bounded_and_the_node_stops_when_it_is_exhausted() {
        NodeDefinition flaky = new NodeDefinition("flaky", "Flaky", List.of(), JoinType.ALL, false, null,
                5_000, 3, 20, RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, null, null);

        WorkflowEngine engine = engineWith(List.of(new TestAgents.FlakyAgent("Flaky", 99, FailureClass.TRANSIENT)));
        WorkflowInstance instance = engine.start(definition(flaky), input(), "1.0.0");
        assertThat(engine.awaitQuiescence(instance.runId(), 20_000)).isTrue();

        assertThat(instance.node("flaky").attempts()).isEqualTo(3);
        assertThat(instance.node("flaky").state()).isEqualTo(NodeState.SAFE_STOPPED);
        assertThat(journal.actionsFor(instance.runId(), "flaky"))
                .filteredOn(AuditActions.NODE_RETRY_SCHEDULED::equals).hasSize(2);
    }

    @Test
    void a_permanent_failure_is_not_retried_at_all() {
        NodeDefinition permanent = new NodeDefinition("hard", "Failing", List.of(), JoinType.ALL, false, null,
                5_000, 3, 20, RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, null, null);

        WorkflowEngine engine = engineWith(List.of(new TestAgents.FailingAgent("Failing", FailureClass.PERMANENT)));
        WorkflowInstance instance = engine.start(definition(permanent), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 20_000);

        assertThat(instance.node("hard").attempts()).isEqualTo(1);
        assertThat(journal.actionsFor(instance.runId(), "hard")).doesNotContain(AuditActions.NODE_RETRY_SCHEDULED);
        assertThat(instance.state()).isEqualTo(InstanceState.SAFE_STOPPED);
    }

    @Test
    void an_attempt_that_exceeds_its_timeout_is_recorded_as_a_timeout() {
        NodeDefinition slow = new NodeDefinition("slow", "Slow", List.of(), JoinType.ALL, false, null,
                200, 1, 20, RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, null, null);

        WorkflowEngine engine = engineWith(List.of(new TestAgents.SlowAgent("Slow", 2_000)));
        WorkflowInstance instance = engine.start(definition(slow), input(), "1.0.0");
        assertThat(engine.awaitQuiescence(instance.runId(), 20_000)).isTrue();

        assertThat(journal.actionsFor(instance.runId(), "slow")).contains(AuditActions.NODE_TIMED_OUT);
        assertThat(instance.node("slow").state()).isEqualTo(NodeState.SAFE_STOPPED);
    }

    @Test
    void a_node_with_a_fallback_completes_degraded_rather_than_stopping_the_run() {
        NodeDefinition withFallback = new NodeDefinition("docs", "Failing", List.of(), JoinType.ALL, false, null,
                5_000, 1, 20, RecoveryMode.ROLLBACKABLE, Criticality.NON_BLOCKING, null, true, false, null, null);

        WorkflowEngine engine = engineWith(List.of(new TestAgents.FailingAgent("Failing", FailureClass.PERMANENT)));
        WorkflowInstance instance = engine.start(definition(withFallback), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 20_000);

        assertThat(instance.node("docs").state()).isEqualTo(NodeState.SUCCEEDED);
        assertThat(instance.node("docs").degraded()).isTrue();
        assertThat(journal.actionsFor(instance.runId(), "docs")).contains(AuditActions.FALLBACK_APPLIED);
        assertThat(instance.state()).isEqualTo(InstanceState.COMPLETED_WITH_LIMITATIONS);
    }

    @Test
    void a_rollbackable_node_rolls_back_and_a_compensatable_node_compensates() {
        NodeDefinition rollbackable = new NodeDefinition("patch", "Failing", List.of(), JoinType.ALL, false, null,
                5_000, 1, 20, RecoveryMode.ROLLBACKABLE, Criticality.BLOCKING, null, false, false, null, null);
        NodeDefinition compensatable = new NodeDefinition("scan", "Failing", List.of(), JoinType.ALL, false, null,
                5_000, 1, 20, RecoveryMode.COMPENSATABLE, Criticality.BLOCKING, null, false, false, null, null);

        WorkflowEngine engine = engineWith(List.of(new TestAgents.FailingAgent("Failing", FailureClass.PERMANENT)));
        WorkflowInstance instance = engine.start(definition(rollbackable, compensatable), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 20_000);

        assertThat(journal.actionsFor(instance.runId(), "patch"))
                .containsSubsequence(AuditActions.ROLLBACK_STARTED, AuditActions.ROLLBACK_COMPLETED);
        assertThat(journal.actionsFor(instance.runId(), "scan"))
                .containsSubsequence(AuditActions.COMPENSATION_STARTED, AuditActions.COMPENSATION_COMPLETED);
        // Neither path silently continues: both end at a human decision.
        assertThat(instance.node("patch").state()).isEqualTo(NodeState.SAFE_STOPPED);
        assertThat(instance.node("scan").state()).isEqualTo(NodeState.SAFE_STOPPED);
    }

    @Test
    void a_blocking_failure_blocks_its_downstream_subgraph() {
        NodeDefinition failing = new NodeDefinition("root", "Failing", List.of(), JoinType.ALL, false, null,
                5_000, 1, 20, RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, null, null);

        WorkflowEngine engine = engineWith(List.of(
                new TestAgents.FailingAgent("Failing", FailureClass.PERMANENT),
                new TestAgents.RecordingAgent("TestAgent")));
        WorkflowInstance instance = engine.start(
                definition(failing, node("downstream", "TestAgent", List.of("root"))), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 20_000);

        assertThat(instance.node("downstream").state()).isEqualTo(NodeState.BLOCKED);
        assertThat(instance.node("downstream").reason()).contains("blocking dependency");
    }

    // ---------------------------------------------------------------- human gates

    @Test
    void a_run_parks_at_a_gate_and_does_not_advance_on_its_own() throws Exception {
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent"),
                new NoopGateAgent()));

        WorkflowInstance instance = engine.start(definition(
                node("before", "TestAgent", List.of()),
                gate("release-gate", List.of("before"), null),
                node("after", "TestAgent", List.of("release-gate"))), input(), "1.0.0");
        assertThat(engine.awaitQuiescence(instance.runId(), 10_000)).isTrue();

        assertThat(instance.state()).isEqualTo(InstanceState.SUSPENDED);
        assertThat(instance.node("release-gate").state()).isEqualTo(NodeState.AWAITING_APPROVAL);

        // Waiting changes nothing. Silence is not approval.
        Thread.sleep(400);
        assertThat(instance.node("release-gate").state()).isEqualTo(NodeState.AWAITING_APPROVAL);
        assertThat(instance.node("after").state()).isEqualTo(NodeState.PENDING);
    }

    @Test
    void an_approval_records_a_decision_and_lets_the_run_continue() {
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent"), new NoopGateAgent()));

        WorkflowInstance instance = engine.start(definition(
                node("before", "TestAgent", List.of()),
                gate("release-gate", List.of("before"), null),
                node("after", "TestAgent", List.of("release-gate"))), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        engine.decide(instance.runId(), "release-gate", "READY", "madhu",
                "All mandatory policies pass and the limitations are accepted.", null);
        assertThat(engine.awaitQuiescence(instance.runId(), 10_000)).isTrue();

        assertThat(instance.state()).isEqualTo(InstanceState.COMPLETED);
        assertThat(instance.node("after").state()).isEqualTo(NodeState.SUCCEEDED);
        assertThat(approvals.find(instance.runId(), "release-gate")).isPresent();
        assertThat(journal.actionsFor(instance.runId(), "release-gate")).contains(AuditActions.APPROVAL_DECIDED);
    }

    @Test
    void a_rejection_terminates_the_run_and_nothing_downstream_runs() {
        TestAgents.RecordingAgent agent = new TestAgents.RecordingAgent("TestAgent");
        WorkflowEngine engine = engineWith(List.of(agent, new NoopGateAgent()));

        WorkflowInstance instance = engine.start(definition(
                node("before", "TestAgent", List.of()),
                gate("release-gate", List.of("before"), null),
                node("after", "TestAgent", List.of("release-gate"))), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        engine.decide(instance.runId(), "release-gate", "REJECT", "madhu",
                "The change is not ready for release.", null);

        assertThat(instance.state()).isEqualTo(InstanceState.REJECTED);
        assertThat(instance.node("release-gate").state()).isEqualTo(NodeState.REJECTED);
        assertThat(agent.invocationsFor("after")).isZero();
    }

    @Test
    void a_decision_without_an_actor_or_a_rationale_is_refused() {
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent"), new NoopGateAgent()));

        WorkflowInstance instance = engine.start(definition(
                node("before", "TestAgent", List.of()),
                gate("release-gate", List.of("before"), null)), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        assertThatThrownBy(() -> engine.decide(instance.runId(), "release-gate", "READY", "  ", "reason", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor");
        assertThatThrownBy(() -> engine.decide(instance.runId(), "release-gate", "READY", "madhu", "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rationale");
    }

    /**
     * The negative test the whole approval model rests on: when the approval window elapses, the
     * gate safe-stops. There is no configuration, flag or timer that turns elapsed time into
     * consent.
     */
    @Test
    void an_elapsed_approval_window_safe_stops_and_never_approves() {
        WorkflowEngine engine = engineWith(
                List.of(new TestAgents.RecordingAgent("TestAgent"), new NoopGateAgent()),
                new EngineSettings(4, Duration.ofMillis(1), 0.0));

        WorkflowInstance instance = engine.start(definition(
                node("before", "TestAgent", List.of()),
                gate("release-gate", List.of("before"), null),
                node("after", "TestAgent", List.of("release-gate"))), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        int expired = engine.expireApprovals();

        assertThat(expired).isEqualTo(1);
        assertThat(instance.node("release-gate").state()).isEqualTo(NodeState.SAFE_STOPPED);
        assertThat(instance.node("release-gate").state()).isNotEqualTo(NodeState.APPROVED);
        assertThat(instance.state()).isEqualTo(InstanceState.SAFE_STOPPED);
        assertThat(approvals.find(instance.runId(), "release-gate")).isEmpty();
        assertThat(journal.actionsFor(instance.runId(), "release-gate"))
                .contains(AuditActions.SAFE_STOP)
                .doesNotContain(AuditActions.APPROVAL_DECIDED);
    }

    @Test
    void a_human_resume_restarts_a_safe_stopped_run() {
        WorkflowEngine engine = engineWith(
                List.of(new TestAgents.RecordingAgent("TestAgent"), new NoopGateAgent()),
                new EngineSettings(4, Duration.ofMillis(1), 0.0));

        WorkflowInstance instance = engine.start(definition(
                node("before", "TestAgent", List.of()),
                gate("release-gate", List.of("before"), null)), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);
        engine.expireApprovals();

        engine.resume(instance.runId(), "madhu", "Reviewer was unavailable; resuming the run.");
        engine.awaitQuiescence(instance.runId(), 10_000);

        assertThat(journal.findByRun(instance.runId()))
                .anySatisfy(e -> assertThat(e.action()).isEqualTo(AuditActions.WORKFLOW_RESUMED));
        // The gate is armed again rather than skipped: resuming does not waive the decision.
        assertThat(instance.node("release-gate").state()).isEqualTo(NodeState.AWAITING_APPROVAL);
    }

    // ---------------------------------------------------------------- replanning

    @Test
    void a_decision_that_supersedes_an_upstream_node_invalidates_its_downstream_closure() {
        TestAgents.RecordingAgent agent = new TestAgents.RecordingAgent("TestAgent");
        WorkflowEngine engine = engineWith(List.of(agent, new NoopGateAgent()));

        WorkflowDefinition definition = definition(
                node("normalize", "TestAgent", List.of()),
                node("quality", "TestAgent", List.of("normalize")),
                gate("clarify", List.of("quality"), "normalize"),
                node("decompose", "TestAgent", List.of("clarify")));

        WorkflowInstance instance = engine.start(definition, input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);
        assertThat(instance.node("quality").state()).isEqualTo(NodeState.SUCCEEDED);

        long versionBefore = instance.definitionVersion();
        engine.decide(instance.runId(), "clarify", "APPROVE", "madhu",
                "Default expiry is 90 days when expiresAt is omitted.", null);
        engine.awaitQuiescence(instance.runId(), 10_000);

        assertThat(instance.definitionVersion()).isEqualTo(versionBefore + 1);
        // 'quality' consumed the superseded artifact, so it was re-executed under the new version.
        assertThat(agent.invocationsFor("quality")).isEqualTo(2);
        assertThat(journal.findByRun(instance.runId()))
                .anySatisfy(e -> assertThat(e.action()).isEqualTo(AuditActions.INVALIDATED))
                .anySatisfy(e -> assertThat(e.action()).isEqualTo(AuditActions.REPLANNED));
        assertThat(instance.replanningLog()).isNotEmpty();
    }

    @Test
    void the_downstream_closure_is_transitive_and_excludes_unrelated_branches() {
        WorkflowDefinition definition = definition(
                node("a", "TestAgent", List.of()),
                node("b", "TestAgent", List.of("a")),
                node("c", "TestAgent", List.of("b")),
                node("unrelated", "TestAgent", List.of()));

        assertThat(WorkflowEngine.downstreamClosure(definition, "a")).containsExactly("b", "c");
        assertThat(WorkflowEngine.downstreamClosure(definition, "b")).containsExactly("c");
        assertThat(WorkflowEngine.downstreamClosure(definition, "unrelated")).isEmpty();
    }

    /**
     * A gate produces no artifact. If lineage stopped at the direct dependencies, everything after
     * a release gate would have an empty provenance chain and the audit story would end one step
     * from where it started.
     */
    @Test
    void provenance_survives_a_gate_that_produces_no_artifact() {
        TestAgents.RecordingAgent agent = new TestAgents.RecordingAgent("TestAgent");
        WorkflowEngine engine = engineWith(List.of(agent, new NoopGateAgent()));

        WorkflowInstance instance = engine.start(definition(
                node("produce", "TestAgent", List.of()),
                gate("release-gate", List.of("produce"), null),
                node("summary", "TestAgent", List.of("release-gate"))), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        engine.decide(instance.runId(), "release-gate", "READY", "madhu",
                "Everything upstream is in order and the limitations are accepted.", null);
        assertThat(engine.awaitQuiescence(instance.runId(), 10_000)).isTrue();

        String summaryArtifactId = instance.node("summary").artifactIds().get(0);
        Artifact summary = instance.artifact(summaryArtifactId).orElseThrow();

        assertThat(summary.inputArtifactIds())
                .as("the summary must trace back through the gate to what 'produce' made")
                .containsExactlyElementsOf(instance.node("produce").artifactIds());
    }

    // ---------------------------------------------------------------- misc

    @Test
    void a_node_whose_agent_is_not_registered_fails_permanently_rather_than_hanging() {
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent")));

        WorkflowInstance instance = engine.start(definition(node("ghost", "NoSuchAgent", List.of())),
                input(), "1.0.0");
        assertThat(engine.awaitQuiescence(instance.runId(), 10_000)).isTrue();

        assertThat(instance.node("ghost").state()).isEqualTo(NodeState.SAFE_STOPPED);
        assertThat(instance.node("ghost").attempts()).isEqualTo(1);
        assertThat(instance.state()).isEqualTo(InstanceState.SAFE_STOPPED);
    }

    @Test
    void a_definition_with_a_cycle_is_refused_before_anything_runs() {
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent")));

        assertThatThrownBy(() -> engine.start(
                definition(node("a", "TestAgent", List.of("b")), node("b", "TestAgent", List.of("a"))),
                input(), "1.0.0"))
                .isInstanceOf(DagValidator.InvalidDefinitionException.class);
    }

    @Test
    void the_run_header_is_persisted_so_a_restart_can_find_it() {
        WorkflowEngine engine = engineWith(List.of(new TestAgents.RecordingAgent("TestAgent")));

        WorkflowInstance instance = engine.start(definition(node("a", "TestAgent", List.of())), input(), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);

        assertThat(instances.find(instance.runId())).hasValueSatisfying(record -> {
            assertThat(record.state()).isEqualTo("COMPLETED");
            assertThat(record.policyVersion()).isEqualTo("1.0.0");
            assertThat(record.terminalAt()).isNotNull();
        });
    }

    /** A gate agent that would fail loudly if the engine ever dispatched a gate node. */
    private static final class NoopGateAgent implements StageAgent {

        @Override
        public String agentType() {
            return "HumanGateAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            throw new AssertionError("A human gate must never be executed by an agent: " + context.nodeId());
        }
    }
}
