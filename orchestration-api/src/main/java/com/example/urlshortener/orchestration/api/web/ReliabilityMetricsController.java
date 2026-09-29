package com.example.urlshortener.orchestration.api.web;

import com.example.urlshortener.orchestration.engine.WorkflowEngine;
import com.example.urlshortener.orchestration.model.TransitionEvent;
import com.example.urlshortener.orchestration.port.InstanceStore;
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

/** Reliability metrics, recomputed from the stored run headers and journal on every request. */
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

        List<InstanceStore.InstanceRecord> headers = engine.headers().stream()
                .filter(r -> runIds == null || runIds.contains(r.runId()))
                .sorted(Comparator.comparing(InstanceStore.InstanceRecord::createdAt))
                .toList();

        List<ReliabilityModel.RunRecord> runs = headers.stream()
                .map(r -> new ReliabilityModel.RunRecord(r.runId(), r.state(), r.createdAt(), r.terminalAt()))
                .toList();

        List<ReliabilityModel.TransitionRecord> transitions = headers.stream()
                .flatMap(r -> journal.findByRun(r.runId()).stream())
                .map(ReliabilityMetricsController::toRecord)
                .toList();

        return calculator.compute(runs, transitions, windowOf(headers));
    }

    private static ReliabilityModel.TransitionRecord toRecord(TransitionEvent event) {
        return new ReliabilityModel.TransitionRecord(event.runId(), event.seq(), event.nodeId(),
                event.action(), event.timestamp(), event.reason());
    }

    private static String windowOf(List<InstanceStore.InstanceRecord> headers) {
        if (headers.isEmpty()) {
            return "no runs";
        }
        Instant from = headers.get(0).createdAt();
        Instant to = headers.stream()
                .map(r -> r.terminalAt() == null ? Instant.now() : r.terminalAt())
                .max(Comparator.naturalOrder())
                .orElse(from);
        return from + "/" + to;
    }
}
