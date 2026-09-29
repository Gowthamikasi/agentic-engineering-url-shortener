package com.example.urlshortener.orchestration.api.web;

import com.example.urlshortener.orchestration.api.config.OrchestrationProperties;
import com.example.urlshortener.orchestration.engine.WorkflowEngine;
import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Decision;
import com.example.urlshortener.orchestration.model.NodeDefinition;
import com.example.urlshortener.orchestration.model.NodeRuntime;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.TransitionEvent;
import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import com.example.urlshortener.orchestration.model.WorkflowInstance;
import com.example.urlshortener.orchestration.port.Journal;
import com.example.urlshortener.telemetry.audit.AuditEvent;
import com.example.urlshortener.telemetry.audit.AuditHasher;
import com.example.urlshortener.telemetry.audit.AuditSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Control-plane API: start runs, inspect them, decide gates, read the evidence. */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowEngine engine;
    private final WorkflowDefinition definition;
    private final Journal journal;
    private final AuditSink audit;
    private final OrchestrationProperties properties;
    private final ObjectMapper json;

    public WorkflowController(WorkflowEngine engine, WorkflowDefinition definition, Journal journal,
                              AuditSink audit, OrchestrationProperties properties, ObjectMapper json) {
        this.engine = engine;
        this.definition = definition;
        this.journal = journal;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Starts a run.
     *
     * <p>waitMs makes the call block until the run settles, which the demo scripts use so a
     * reviewer sees a finished run instead of polling. It does not change how the run executes.
     */
    @PostMapping
    public ResponseEntity<WorkflowDto.RunSummary> start(
            @Valid @RequestBody WorkflowDto.StartRunRequest request,
            @RequestParam(value = "waitMs", defaultValue = "0") long waitMs) {

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", request.text());
        input.put("requirementId", Optional.ofNullable(request.requirementId()).orElse("REQ-UNSPECIFIED"));
        input.put("kind", Optional.ofNullable(request.kind()).orElse("Unclassified"));
        input.put("actor", request.actor());
        if (request.faults() != null) {
            input.put("faults", request.faults());
        }
        if (request.dependencyScan() != null) {
            input.put("dependencyScan", request.dependencyScan());
        }
        if (request.tddEvidence() != null) {
            input.put("tddEvidence", request.tddEvidence());
        }

        String policyVersion = Optional.ofNullable(request.policyVersion()).orElse(properties.getPolicyVersion());
        WorkflowInstance instance = engine.start(definition, input, policyVersion);

        if (waitMs > 0) {
            engine.awaitQuiescence(instance.runId(), Math.min(waitMs, 120_000));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(summaryOf(instance));
    }

    @GetMapping
    public List<WorkflowDto.RunSummary> list() {
        return engine.all().stream().map(this::summaryOf).toList();
    }

    @GetMapping("/{runId}")
    public WorkflowDto.RunDetail detail(@PathVariable String runId) {
        WorkflowInstance instance = require(runId);

        List<WorkflowDto.NodeView> nodes = instance.definition().nodes().stream()
                .map(node -> WorkflowDto.NodeView.of(
                        instance.node(node.id()).snapshot(waitingOn(instance, node))))
                .toList();

        return new WorkflowDto.RunDetail(instance.runId(), instance.definitionName(), instance.definitionVersion(),
                instance.policyVersion(), instance.state().name(), instance.terminalOutcome(),
                instance.createdAt(), instance.terminalAt(),
                Boolean.TRUE.equals(instance.facts().get("policy.releaseBlocked")),
                nodes,
                instance.decisions().stream().map(WorkflowDto.DecisionView::of).toList(),
                instance.assumptions(), instance.replanningLog(), instance.facts());
    }

    @PostMapping("/{runId}/resume")
    public WorkflowDto.RunSummary resume(@PathVariable String runId,
                                         @Valid @RequestBody WorkflowDto.ResumeRequest request,
                                         @RequestParam(value = "waitMs", defaultValue = "0") long waitMs) {
        engine.resume(runId, request.actor(), request.reason());
        if (waitMs > 0) {
            engine.awaitQuiescence(runId, Math.min(waitMs, 120_000));
        }
        return summaryOf(require(runId));
    }

    @PostMapping("/{runId}/safe-stop")
    public WorkflowDto.RunSummary safeStop(@PathVariable String runId,
                                           @Valid @RequestBody WorkflowDto.ResumeRequest request) {
        engine.safeStop(runId, request.actor(), request.reason());
        return summaryOf(require(runId));
    }

    @GetMapping("/{runId}/gates")
    public List<WorkflowDto.GateView> gates(@PathVariable String runId) {
        WorkflowInstance instance = require(runId);

        return instance.definition().nodes().stream()
                .filter(NodeDefinition::requiresApproval)
                .map(node -> {
                    NodeRuntime runtime = instance.node(node.id());
                    Optional<Decision> decision = instance.decisionForGate(node.effectiveGateId());
                    Instant requestedAt = runtime.state() == NodeState.AWAITING_APPROVAL
                            ? runtime.startedAt() == null ? instance.createdAt() : runtime.startedAt()
                            : null;
                    return new WorkflowDto.GateView(node.effectiveGateId(), node.id(), runtime.state().name(),
                            requestedAt,
                            requestedAt == null ? null : requestedAt.plus(properties.getApprovalTimeout()),
                            node.description(), upstreamArtifactIds(instance, node),
                            decision.map(Decision::decision).orElse(null),
                            decision.map(Decision::actor).orElse(null));
                })
                .toList();
    }

    /**
     * Records a human decision.
     *
     * <p>This is the only way a gate moves forward. Nothing approves on the caller's behalf and
     * no timer does it either (REQ-D-010).
     */
    @PostMapping("/{runId}/gates/{gateId}/decision")
    public ResponseEntity<WorkflowDto.DecisionView> decide(
            @PathVariable String runId,
            @PathVariable String gateId,
            @Valid @RequestBody WorkflowDto.DecisionRequest request,
            @RequestParam(value = "waitMs", defaultValue = "0") long waitMs) {

        Decision decision = engine.decide(runId, gateId, request.decision(), request.actor(),
                request.rationale(), request.conditions());

        if (waitMs > 0) {
            engine.awaitQuiescence(runId, Math.min(waitMs, 120_000));
        }
        return ResponseEntity.ok(WorkflowDto.DecisionView.of(decision));
    }

    /** The transition journal, in the order it happened. */
    @GetMapping("/{runId}/history")
    public List<Map<String, Object>> history(@PathVariable String runId) {
        return journal.findByRun(runId).stream().map(WorkflowController::historyRow).toList();
    }

    /** The audit trail as JSON Lines, with a header saying whether the hash chain verifies. */
    @GetMapping(value = "/{runId}/audit", produces = "application/x-ndjson")
    public ResponseEntity<String> auditTrail(@PathVariable String runId) {
        List<AuditEvent> events = audit.findByRun(runId);
        int broken = AuditHasher.verifyChain(events);

        StringBuilder body = new StringBuilder();
        for (AuditEvent event : events) {
            body.append(writeJson(auditRow(event))).append('\n');
        }
        return ResponseEntity.ok()
                .header("X-Audit-Chain", broken < 0 ? "intact" : "broken-at-row-" + broken)
                .header("X-Audit-Rows", Integer.toString(events.size()))
                .body(body.toString());
    }

    @GetMapping("/{runId}/artifacts")
    public List<WorkflowDto.ArtifactView> artifacts(@PathVariable String runId) {
        return require(runId).artifacts().stream().map(this::artifactView).toList();
    }

    /**
     * Walks an artifact back to the requirement it came from.
     *
     * <p>Each step names the node that produced it, what it consumed, and the decisions in
     * force at the time.
     */
    @GetMapping("/{runId}/lineage/{artifactId}")
    public WorkflowDto.LineageResponse lineage(@PathVariable String runId, @PathVariable String artifactId) {
        WorkflowInstance instance = require(runId);

        List<WorkflowDto.LineageStep> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> decisionIds = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>(List.of(artifactId));

        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            if (!seen.add(current)) {
                continue;
            }
            instance.artifact(current).ifPresent(artifact -> {
                chain.add(WorkflowDto.LineageStep.of(artifact));
                decisionIds.addAll(artifact.decisionIds());
                frontier.addAll(artifact.inputArtifactIds());
            });
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("No such artifact in run " + runId + ": " + artifactId);
        }

        List<WorkflowDto.DecisionView> decisions = instance.decisions().stream()
                .filter(d -> decisionIds.contains(d.decisionId()))
                .map(WorkflowDto.DecisionView::of)
                .toList();

        return new WorkflowDto.LineageResponse(runId, artifactId, List.copyOf(chain), decisions);
    }

    /** The graph as Mermaid, coloured by what each node actually did. */
    @GetMapping(value = "/{runId}/graph", produces = MediaType.TEXT_PLAIN_VALUE)
    public String graph(@PathVariable String runId,
                        @RequestParam(value = "format", defaultValue = "mermaid") String format) {

        WorkflowInstance instance = require(runId);
        if (!"mermaid".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("Only the 'mermaid' format is supported.");
        }

        StringBuilder mermaid = new StringBuilder("flowchart TD\n");
        for (NodeDefinition node : instance.definition().nodes()) {
            NodeState state = instance.node(node.id()).state();
            String label = node.requiresApproval() ? node.id() + " 🛑" : node.id();
            mermaid.append("  ").append(safeId(node.id()))
                    .append("[\"").append(label).append("<br/>").append(state.name()).append("\"]")
                    .append(":::").append(cssClass(state)).append('\n');
        }
        for (NodeDefinition node : instance.definition().nodes()) {
            for (String dependency : node.dependsOn()) {
                mermaid.append("  ").append(safeId(dependency)).append(" --> ").append(safeId(node.id())).append('\n');
            }
        }
        mermaid.append("""
                  classDef ok fill:#d7f5dd,stroke:#2f8f46
                  classDef run fill:#dbe9ff,stroke:#2b6cb0
                  classDef retry fill:#fff1cc,stroke:#b7791f
                  classDef gate fill:#ffe8cc,stroke:#c05621
                  classDef bad fill:#ffe3e3,stroke:#c53030
                  classDef skip fill:#eeeeee,stroke:#999999,stroke-dasharray:3 3
                  classDef pend fill:#ffffff,stroke:#999999
                """);
        return mermaid.toString();
    }

    private WorkflowInstance require(String runId) {
        return engine.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("No such run: " + runId));
    }

    private WorkflowDto.RunSummary summaryOf(WorkflowInstance instance) {
        String base = "/api/v1/workflows/" + instance.runId();
        return new WorkflowDto.RunSummary(instance.runId(), instance.definitionName(),
                instance.definitionVersion(), instance.policyVersion(), instance.state().name(),
                instance.terminalOutcome(), instance.createdAt(), instance.terminalAt(),
                Map.of("self", base, "graph", base + "/graph", "audit", base + "/audit",
                        "history", base + "/history", "gates", base + "/gates",
                        "artifacts", base + "/artifacts"));
    }

    /** Which dependencies a pending node is still waiting for. */
    private static List<String> waitingOn(WorkflowInstance instance, NodeDefinition node) {
        if (instance.node(node.id()).state() != NodeState.PENDING) {
            return List.of();
        }
        return node.dependsOn().stream()
                .filter(dep -> !instance.node(dep).state().satisfiesDependency())
                .toList();
    }

    private static List<String> upstreamArtifactIds(WorkflowInstance instance, NodeDefinition node) {
        List<String> ids = new ArrayList<>();
        node.dependsOn().forEach(dep -> ids.addAll(instance.node(dep).artifactIds()));
        return ids;
    }

    private WorkflowDto.ArtifactView artifactView(Artifact artifact) {
        Object content;
        try {
            content = json.readTree(artifact.contentJson());
        } catch (Exception e) {
            content = artifact.contentJson();
        }
        return new WorkflowDto.ArtifactView(artifact.artifactId(), artifact.nodeId(), artifact.type(),
                artifact.version(), artifact.sha256(), artifact.inputArtifactIds(), artifact.decisionIds(),
                artifact.producedAt(), content);
    }

    private static Map<String, Object> historyRow(TransitionEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", event.seq());
        row.put("ts", event.timestamp().toString());
        row.put("nodeId", event.nodeId());
        row.put("from", event.fromState() == null ? null : event.fromState().name());
        row.put("to", event.toState() == null ? null : event.toState().name());
        row.put("action", event.action());
        row.put("actorType", event.actorType());
        row.put("actorId", event.actorId());
        row.put("result", event.result());
        row.put("reason", event.reason());
        row.put("definitionVersion", event.definitionVersion());
        return row;
    }

    private static Map<String, Object> auditRow(AuditEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", event.seq());
        row.put("ts", event.timestamp().toString());
        row.put("actorType", event.actorType().name());
        row.put("actorId", event.actorId());
        row.put("action", event.action());
        row.put("target", event.targetId());
        row.put("result", event.result());
        row.put("reason", event.reason());
        row.put("policyVersion", event.policyVersion());
        row.put("definitionVersion", event.definitionVersion());
        row.put("prevHash", event.prevHash());
        row.put("hash", event.hash());
        return row;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"could not serialise audit row\"}";
        }
    }

    private static String safeId(String nodeId) {
        return nodeId.replace('-', '_');
    }

    private static String cssClass(NodeState state) {
        return switch (state) {
            case SUCCEEDED, APPROVED, ROLLED_BACK, COMPENSATED -> "ok";
            case RUNNING, READY -> "run";
            case RETRYING, TIMED_OUT, FALLING_BACK, ROLLING_BACK, COMPENSATING -> "retry";
            case AWAITING_APPROVAL -> "gate";
            case FAILED, REJECTED, SAFE_STOPPED, BLOCKED -> "bad";
            case SKIPPED, INVALIDATED -> "skip";
            case PENDING -> "pend";
        };
    }
}
