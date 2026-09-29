package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.Criticality;
import com.example.urlshortener.orchestration.model.InstanceState;
import com.example.urlshortener.orchestration.model.JoinType;
import com.example.urlshortener.orchestration.model.NodeDefinition;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.RecoveryMode;
import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import com.example.urlshortener.orchestration.model.WorkflowInstance;
import com.example.urlshortener.orchestration.port.InstanceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Rebuilding a finished run from the stores, the way a restarted process has to. */
class RunRehydratorTest {

    private InMemoryStores.MemoryJournal journal;
    private InMemoryStores.MemoryApprovalStore approvals;
    private InMemoryStores.MemoryInstanceStore instances;
    private InMemoryStores.MemoryArtifactStore artifacts;
    private InMemoryStores.MemoryAuditSink audit;
    private WorkflowEngine engine;

    private static final WorkflowDefinition DEFINITION = new WorkflowDefinition("test", 1, "test", List.of(
            node("produce", List.of()),
            gate("release-gate", List.of("produce")),
            node("summary", List.of("release-gate"))));

    private static NodeDefinition node(String id, List<String> dependsOn) {
        return new NodeDefinition(id, "TestAgent", dependsOn, JoinType.ALL, false, null, 5_000, 1, 50,
                RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, null, List.of(), id);
    }

    private static NodeDefinition gate(String id, List<String> dependsOn) {
        return new NodeDefinition(id, "HumanGateAgent", dependsOn, JoinType.ALL, true, id, 5_000, 1, 50,
                RecoveryMode.NONE, Criticality.BLOCKING, null, false, false, null, List.of(), id);
    }

    @BeforeEach
    void setUp() {
        journal = new InMemoryStores.MemoryJournal();
        approvals = new InMemoryStores.MemoryApprovalStore();
        instances = new InMemoryStores.MemoryInstanceStore();
        artifacts = new InMemoryStores.MemoryArtifactStore();
        audit = new InMemoryStores.MemoryAuditSink();
        engine = new WorkflowEngine(List.of(new TestAgents.RecordingAgent("TestAgent"), new SilentGateAgent()),
                List.of(DEFINITION), journal, approvals, instances, artifacts, audit, Clock.systemUTC(),
                EngineSettings.defaults());
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    /** Runs a full workflow and returns its id, leaving the stores populated. */
    private String completeRun() {
        WorkflowInstance instance = engine.start(DEFINITION,
                Map.of("text", "a requirement", "actor", "madhu", "kind", "Greenfield"), "1.0.0");
        engine.awaitQuiescence(instance.runId(), 10_000);
        engine.decide(instance.runId(), "release-gate", "READY", "madhu",
                "Everything upstream is in order.", null);
        engine.awaitQuiescence(instance.runId(), 10_000);
        assertThat(instance.state()).isEqualTo(InstanceState.COMPLETED);
        return instance.runId();
    }

    /** A fresh rehydrator over the same stores stands in for a restarted process. */
    private RunRehydrator afterRestart() {
        return new RunRehydrator(instances, journal, artifacts, approvals,
                name -> Optional.of(DEFINITION), new ObjectMapper());
    }

    @Test
    void a_completed_run_is_rebuilt_with_its_terminal_state_and_outcome() {
        String runId = completeRun();

        WorkflowInstance rebuilt = afterRestart().rehydrate(runId).orElseThrow();

        assertThat(rebuilt.runId()).isEqualTo(runId);
        assertThat(rebuilt.state()).isEqualTo(InstanceState.COMPLETED);
        assertThat(rebuilt.terminalOutcome()).isNotBlank();
        assertThat(rebuilt.terminalAt()).isNotNull();
        assertThat(rebuilt.policyVersion()).isEqualTo("1.0.0");
    }

    @Test
    void node_states_and_attempt_counts_come_back_from_the_journal() {
        String runId = completeRun();

        WorkflowInstance rebuilt = afterRestart().rehydrate(runId).orElseThrow();

        assertThat(rebuilt.node("produce").state()).isEqualTo(NodeState.SUCCEEDED);
        assertThat(rebuilt.node("produce").attempts()).isEqualTo(1);
        assertThat(rebuilt.node("summary").state()).isEqualTo(NodeState.SUCCEEDED);
        assertThat(rebuilt.node("release-gate").state()).isEqualTo(NodeState.SUCCEEDED);
    }

    /** Artifacts are what lineage walks, so losing them would take the lineage endpoint with them. */
    @Test
    void artifacts_and_their_provenance_survive() {
        String runId = completeRun();

        WorkflowInstance rebuilt = afterRestart().rehydrate(runId).orElseThrow();

        assertThat(rebuilt.artifacts()).isNotEmpty();
        assertThat(rebuilt.node("produce").artifactIds()).isNotEmpty();

        String artifactId = rebuilt.node("summary").artifactIds().get(0);
        assertThat(rebuilt.artifact(artifactId)).isPresent();
        assertThat(rebuilt.artifact(artifactId).orElseThrow().inputArtifactIds()).isNotEmpty();
    }

    @Test
    void the_human_decision_and_its_rationale_survive() {
        String runId = completeRun();

        WorkflowInstance rebuilt = afterRestart().rehydrate(runId).orElseThrow();

        assertThat(rebuilt.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.gateId()).isEqualTo("release-gate");
            assertThat(decision.decision()).isEqualTo("READY");
            assertThat(decision.actor()).isEqualTo("madhu");
            assertThat(decision.rationale()).isNotBlank();
        });
    }

    @Test
    void facts_survive_so_the_policy_verdict_can_still_be_read() {
        String runId = completeRun();

        WorkflowInstance rebuilt = afterRestart().rehydrate(runId).orElseThrow();

        assertThat(rebuilt.facts()).isNotEmpty();
    }

    @Test
    void a_run_that_was_never_recorded_rehydrates_to_nothing() {
        assertThat(afterRestart().rehydrate("run_does_not_exist")).isEmpty();
    }

    /** The engine falls back to the stores, so a caller does not have to know where a run lives. */
    @Test
    void find_rebuilds_a_run_that_is_no_longer_in_memory() {
        String runId = completeRun();

        WorkflowEngine restarted = new WorkflowEngine(
                List.of(new TestAgents.RecordingAgent("TestAgent"), new SilentGateAgent()),
                List.of(DEFINITION), journal, approvals, instances, artifacts, audit, Clock.systemUTC(),
                EngineSettings.defaults());
        try {
            // A fresh engine holds nothing in memory, exactly like a process that just started.
            restarted.start(DEFINITION, Map.of("text", "x", "actor", "madhu"), "1.0.0");
            assertThat(restarted.find(runId)).isPresent();
            assertThat(restarted.find(runId).orElseThrow().state()).isEqualTo(InstanceState.COMPLETED);
        } finally {
            restarted.close();
        }
    }

    @Test
    void headers_list_every_run_that_was_ever_recorded() {
        String runId = completeRun();

        List<InstanceStore.InstanceRecord> headers = engine.headers();

        assertThat(headers).extracting(InstanceStore.InstanceRecord::runId).contains(runId);
    }

    /** A gate node the engine holds; it must never actually be dispatched. */
    private static final class SilentGateAgent implements StageAgent {

        @Override
        public String agentType() {
            return "HumanGateAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            throw new AssertionError("a gate must not be executed: " + context.nodeId());
        }
    }
}
