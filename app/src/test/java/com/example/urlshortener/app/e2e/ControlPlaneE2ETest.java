package com.example.urlshortener.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.urlshortener.app.Application;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A governed run driven over HTTP, the way a reviewer or a pipeline would drive it.
 *
 * <p>The engine's own tests use stub agents in-process. These use the real workflow, the real
 * agents and the real API, so they show the pieces line up: the run parks, the decision
 * endpoint releases it, and the audit trail verifies afterwards.
 */
@Tag("e2e")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControlPlaneE2ETest {

    private static final String OPERATOR = "demo-operator-key";
    private static final long WAIT_MS = 60_000;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<String> request(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", OPERATOR);
        return new HttpEntity<>(body, headers);
    }

    private JsonNode startRun(String body) {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                url("/api/v1/workflows?waitMs=" + WAIT_MS), request(body), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private JsonNode run(String runId) {
        return rest.exchange(url("/api/v1/workflows/" + runId), HttpMethod.GET,
                request(null), JsonNode.class).getBody();
    }

    private ResponseEntity<JsonNode> decide(String runId, String gate, String decision, String rationale) {
        String body = "{\"decision\":\"" + decision + "\",\"actor\":\"madhu\",\"rationale\":\""
                + rationale + "\"}";
        return rest.postForEntity(
                url("/api/v1/workflows/" + runId + "/gates/" + gate + "/decision?waitMs=" + WAIT_MS),
                request(body), JsonNode.class);
    }

    private static JsonNode node(JsonNode run, String nodeId) {
        for (JsonNode n : run.get("nodes")) {
            if (nodeId.equals(n.get("id").asText())) {
                return n;
            }
        }
        throw new AssertionError("no such node: " + nodeId);
    }

    private static final String GREENFIELD = """
            {"definition":"sdlc","requirementId":"REQ-E2E-A","kind":"Greenfield","actor":"madhu",
             "text":"A client may supply expiresAt (ISO-8601 UTC) when creating a link. A redirect for an expired link returns 410 Gone with a JSON problem body. An expiresAt in the past is rejected with 400. Stats must still show total clicks for an expired link.",
             "dependencyScan":{"ran":true,"high":0},
             "tddEvidence":{"redThenGreen":true,"ref":"e2e"}}""";

    private static final String AMBIGUOUS = """
            {"definition":"sdlc","requirementId":"REQ-E2E-C","kind":"Unclassified","actor":"madhu",
             "text":"Links should expire after a while and we should show popular links.",
             "dependencyScan":{"ran":true,"high":0}}""";

    @Test
    void a_clear_requirement_runs_to_the_release_gate_and_completes_when_a_human_approves() {
        String runId = startRun(GREENFIELD).get("runId").asText();

        JsonNode parked = run(runId);
        assertThat(parked.get("state").asText()).isEqualTo("SUSPENDED");
        assertThat(node(parked, "release-gate").get("state").asText()).isEqualTo("AWAITING_APPROVAL");

        // REQ-D-009: a complete requirement must not be stopped by a gate.
        assertThat(node(parked, "clarify").get("state").asText()).isEqualTo("SKIPPED");
        assertThat(node(parked, "implement").get("state").asText()).isEqualTo("SUCCEEDED");

        ResponseEntity<JsonNode> decision = decide(runId, "release-gate", "READY",
                "All mandatory policies pass and the contract change is additive.");
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode completed = run(runId);
        assertThat(completed.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("releaseBlocked").asBoolean()).isFalse();
        assertThat(node(completed, "summary").get("state").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void the_parallel_verification_nodes_overlap_and_policy_eval_waits_for_all_of_them() {
        String runId = startRun(GREENFIELD).get("runId").asText();

        JsonNode history = rest.exchange(url("/api/v1/workflows/" + runId + "/history"),
                HttpMethod.GET, request(null), JsonNode.class).getBody();

        List<String> siblings = List.of("unit-tests", "contract-tests", "security-scan", "docs");
        List<String> starts = new ArrayList<>();
        String lastSiblingSuccess = null;
        String policyEvalStart = null;

        for (JsonNode event : history) {
            // Workflow-level rows have no node id, and List.of(...).contains(null) throws.
            String nodeId = event.hasNonNull("nodeId") ? event.get("nodeId").asText() : "";
            String action = event.get("action").asText();
            String ts = event.get("ts").asText();

            if (siblings.contains(nodeId) && "NodeStarted".equals(action)) {
                starts.add(ts);
            }
            if (siblings.contains(nodeId) && "NodeSucceeded".equals(action)) {
                lastSiblingSuccess = ts;
            }
            if ("policy-eval".equals(nodeId) && "NodeStarted".equals(action) && policyEvalStart == null) {
                policyEvalStart = ts;
            }
        }

        assertThat(starts).as("every verification sibling must have started").hasSize(siblings.size());
        assertThat(policyEvalStart).isNotNull();
        // The join, as an ordering fact.
        assertThat(lastSiblingSuccess).isLessThanOrEqualTo(policyEvalStart);
    }

    @Test
    void the_audit_trail_written_during_a_real_run_verifies_afterwards() {
        String runId = startRun(GREENFIELD).get("runId").asText();
        decide(runId, "release-gate", "READY", "Approved for the audit chain check.");

        ResponseEntity<String> audit = rest.exchange(url("/api/v1/workflows/" + runId + "/audit"),
                HttpMethod.GET, request(null), String.class);

        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audit.getHeaders().getFirst("X-Audit-Chain")).isEqualTo("intact");
        assertThat(Integer.parseInt(audit.getHeaders().getFirst("X-Audit-Rows"))).isGreaterThan(20);
        assertThat(audit.getBody()).contains("WorkflowCreated").contains("ApprovalDecided");
    }

    @Test
    void an_artifact_can_be_traced_back_to_the_requirement_it_came_from() {
        String runId = startRun(GREENFIELD).get("runId").asText();
        decide(runId, "release-gate", "READY", "Approved so the summary artifact exists to trace.");

        JsonNode lineage = rest.exchange(
                url("/api/v1/workflows/" + runId + "/lineage/summary:RunSummary:v1"),
                HttpMethod.GET, request(null), JsonNode.class).getBody();

        List<String> chain = new ArrayList<>();
        lineage.get("chain").forEach(step -> chain.add(step.get("artifactId").asText()));

        assertThat(chain).contains("summary:RunSummary:v1")
                .as("provenance must survive the release gate and reach the original requirement")
                .contains("ingest:RawRequirement:v1");
    }

    @Test
    void a_vague_requirement_stops_at_the_clarification_gate_without_implementing_anything() {
        String runId = startRun(AMBIGUOUS).get("runId").asText();

        JsonNode parked = run(runId);

        assertThat(parked.get("state").asText()).isEqualTo("AWAITING_CLARIFICATION");
        assertThat(node(parked, "clarify").get("state").asText()).isEqualTo("AWAITING_APPROVAL");
        // The point of the scenario: it did not guess what "a while" means.
        assertThat(node(parked, "implement").get("attempts").asInt()).isZero();
        assertThat(node(parked, "implement").get("state").asText()).isEqualTo("PENDING");
    }

    @Test
    void answering_the_clarification_replans_the_run_onto_a_new_definition_version() {
        String runId = startRun(AMBIGUOUS).get("runId").asText();
        assertThat(run(runId).get("definitionVersion").asLong()).isEqualTo(1);

        decide(runId, "clarify", "APPROVE",
                "Default expiry is 90 days when expiresAt is omitted; popular means the top 10 by "
                        + "clicks over the trailing 7 days behind an authenticated endpoint.");

        JsonNode replanned = run(runId);
        assertThat(replanned.get("definitionVersion").asLong()).isEqualTo(2);
        assertThat(replanned.get("replanning")).isNotEmpty();

        decide(runId, "release-gate", "READY", "The clarified requirement is now testable.");
        assertThat(run(runId).get("state").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void a_gate_cannot_be_decided_twice_and_refuses_an_unexplained_decision() {
        String runId = startRun(GREENFIELD).get("runId").asText();

        String noRationale = "{\"decision\":\"READY\",\"actor\":\"madhu\",\"rationale\":\"\"}";
        ResponseEntity<String> refused = rest.postForEntity(
                url("/api/v1/workflows/" + runId + "/gates/release-gate/decision"),
                request(noRationale), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(decide(runId, "release-gate", "READY", "A properly explained approval.")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // The gate is settled, so deciding again is a conflict rather than a no-op.
        ResponseEntity<String> again = rest.postForEntity(
                url("/api/v1/workflows/" + runId + "/gates/release-gate/decision"),
                request("{\"decision\":\"READY\",\"actor\":\"madhu\",\"rationale\":\"Trying again.\"}"),
                String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reliability_metrics_are_derived_from_real_runs_and_labelled_as_demonstration_data() {
        String runId = startRun(GREENFIELD).get("runId").asText();
        decide(runId, "release-gate", "READY", "Approved so the run counts towards the metrics.");

        JsonNode metrics = rest.exchange(url("/api/v1/metrics/reliability"), HttpMethod.GET,
                request(null), JsonNode.class).getBody();

        assertThat(metrics.get("dataClass").asText()).isEqualTo("DEMONSTRATION");
        assertThat(metrics.get("note").asText()).contains("not production statistics");
        assertThat(metrics.get("runs").get("total").asInt()).isPositive();
        assertThat(metrics.get("nodes").get("attempts").asInt()).isPositive();
        // Unrecovered failures must stay out of MTTR.
        assertThat(metrics.get("recovery").has("unrecoveredFailureEvents")).isTrue();
    }

    @Test
    void the_control_plane_requires_its_own_scope() {
        HttpHeaders readOnly = new HttpHeaders();
        readOnly.setContentType(MediaType.APPLICATION_JSON);
        readOnly.set("X-Api-Key", "demo-reviewer-key");

        ResponseEntity<String> refused = rest.postForEntity(url("/api/v1/workflows"),
                new HttpEntity<>(GREENFIELD, readOnly), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void the_graph_endpoint_reports_the_path_taken_and_the_path_skipped() {
        String runId = startRun(GREENFIELD).get("runId").asText();

        String graph = rest.exchange(url("/api/v1/workflows/" + runId + "/graph"), HttpMethod.GET,
                request(null), String.class).getBody();

        assertThat(graph).startsWith("flowchart TD");
        assertThat(graph).contains("release_gate").contains("SKIPPED").contains("SUCCEEDED");
    }
}
