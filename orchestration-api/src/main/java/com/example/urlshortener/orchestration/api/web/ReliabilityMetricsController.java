package com.example.urlshortener.orchestration.api.web;

import com.example.urlshortener.orchestration.engine.WorkflowEngine;
import com.example.urlshortener.orchestration.model.TransitionEvent;
import com.example.urlshortener.orchestration.model.WorkflowInstance;
import com.example.urlshortener.orchestration.port.Journal;
import com.example.urlshortener.telemetry.metrics.ReliabilityMetricsCalculator;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Reliability metrics, derived from the journal on every request.
 *
 * <p>Nothing here is stored or hand-maintained: success rate, retry frequency and MTTR are
 * recomputed from the same rows a reviewer can read at {@code /audit}. Every response is labelled
 * {@code DEMONSTRATION} (REQ-D-013), because these are numbers from a handful of scripted runs,
 * not production statistics, and presenting them as the latter would be the easiest lie in the
 * whole system to tell.
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class ReliabilityMetricsController {

    private final WorkflowEngine engine;
    private final Journal journal;
    private final ReliabilityMetricsCalculator calculator;

    public ReliabilityMetricsController(WorkflowEngine engine, Journal journal,
                                        ReliabilityMetricsCalculator calculator) {
        this.engine = engine;
        this.journal = journal;
        this.calculator = calculator;
    }

    @GetMapping("/reliability")
    public ReliabilityModel.ReliabilityReport reliability(
            @RequestParam(value = "runId", required = false) List<String> runIds) {

        List<WorkflowInstance> instances = engine.all().stream()
                .filter(i -> runIds == null || runIds.contains(i.runId()))
                .sorted(Comparator.comparing(WorkflowInstance::createdAt))
                .toList();

        List<ReliabilityModel.RunRecord> runs = instances.stream()
                .map(i -> new ReliabilityModel.RunRecord(i.runId(), i.state().name(), i.createdAt(), i.terminalAt()))
                .toList();

        List<ReliabilityModel.TransitionRecord> transitions = instances.stream()
                .flatMap(i -> journal.findByRun(i.runId()).stream())
                .map(ReliabilityMetricsController::toRecord)
                .toList();

        return calculator.compute(runs, transitions, windowOf(instances));
    }

    private static ReliabilityModel.TransitionRecord toRecord(TransitionEvent event) {
        return new ReliabilityModel.TransitionRecord(event.runId(), event.seq(), event.nodeId(),
                event.action(), event.timestamp(), event.reason());
    }

    private static String windowOf(List<WorkflowInstance> instances) {
        if (instances.isEmpty()) {
            return "no runs";
        }
        Instant from = instances.get(0).createdAt();
        Instant to = instances.stream()
                .map(i -> i.terminalAt() == null ? Instant.now() : i.terminalAt())
                .max(Comparator.naturalOrder())
                .orElse(from);
        return from + "/" + to;
    }
}
