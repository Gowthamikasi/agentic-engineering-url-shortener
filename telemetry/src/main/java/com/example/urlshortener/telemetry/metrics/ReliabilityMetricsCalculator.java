package com.example.urlshortener.telemetry.metrics;

import com.example.urlshortener.telemetry.audit.AuditActions;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.FailureEvent;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.LatencyStats;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.NodeStats;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.Population;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.RecoveryStats;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.ReliabilityReport;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.RunRecord;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.RunStats;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.TransitionRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Works out reliability metrics, including MTTR, from the journal. */
public final class ReliabilityMetricsCalculator {

    private static final Set<String> FAILURE_ACTIONS = Set.of(AuditActions.NODE_FAILED, AuditActions.NODE_TIMED_OUT);

    private static final Map<String, String> RECOVERY_MECHANISMS = Map.of(
            AuditActions.NODE_RETRY_SCHEDULED, "Retry",
            AuditActions.ROLLBACK_STARTED, "Rollback",
            AuditActions.COMPENSATION_STARTED, "Compensation",
            AuditActions.FALLBACK_APPLIED, "Fallback",
            AuditActions.WORKFLOW_RESUMED, "HumanResume");

    public ReliabilityReport compute(List<RunRecord> runs, List<TransitionRecord> transitions, String window) {
        List<TransitionRecord> ordered = transitions.stream()
                .sorted(Comparator.comparing(TransitionRecord::runId).thenComparingLong(TransitionRecord::seq))
                .toList();

        List<FailureEvent> failureEvents = extractFailureEvents(ordered);
        List<FailureEvent> recovered = failureEvents.stream().filter(FailureEvent::recovered).toList();
        List<FailureEvent> unrecovered = failureEvents.stream().filter(f -> !f.recovered()).toList();

        long totalRecoveryMs = recovered.stream().mapToLong(FailureEvent::durationMs).sum();
        Long mttr = recovered.isEmpty() ? null : totalRecoveryMs / recovered.size();

        return new ReliabilityReport(
                ReliabilityReport.DATA_CLASS,
                ReliabilityReport.NOTE,
                new Population(runs.stream().map(RunRecord::runId).toList(), window, List.of()),
                runStats(runs),
                nodeStats(ordered, failureEvents.size()),
                latencyStats(runs),
                new RecoveryStats(recovered, recovered.size(), totalRecoveryMs, mttr, unrecovered, unrecovered.size()));
    }

    // ---------------------------------------------------------------- failure events

    private List<FailureEvent> extractFailureEvents(List<TransitionRecord> ordered) {
        Map<String, OpenFailure> open = new LinkedHashMap<>();
        List<FailureEvent> closed = new ArrayList<>();

        for (TransitionRecord t : ordered) {
            String action = t.action();

            // A human resume is a run-level event: it can start recovery for every open failure in that run.
            if (AuditActions.WORKFLOW_RESUMED.equals(action)) {
                open.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(t.runId() + "\u0000"))
                        .forEach(e -> e.getValue().startRecovery(t.timestamp(), "HumanResume"));
                continue;
            }
            if (t.nodeId() == null) {
                continue;
            }
            String key = t.runId() + "\u0000" + t.nodeId();

            if (FAILURE_ACTIONS.contains(action)) {
                open.putIfAbsent(key, new OpenFailure(t.runId(), t.nodeId(), t.timestamp()));
            } else if (RECOVERY_MECHANISMS.containsKey(action)) {
                OpenFailure failure = open.get(key);
                if (failure != null) {
                    failure.startRecovery(t.timestamp(), RECOVERY_MECHANISMS.get(action));
                }
            } else if (AuditActions.NODE_SUCCEEDED.equals(action)) {
                OpenFailure failure = open.remove(key);
                if (failure != null) {
                    closed.add(failure.close(t.timestamp()));
                }
            }
        }

        open.values().forEach(f -> closed.add(f.abandon()));
        closed.sort(Comparator.comparing(FailureEvent::failureDetectedAt));
        return closed;
    }

    /** Mutable accumulator for a failure that has been detected but not yet resolved. */
    private static final class OpenFailure {
        private final String runId;
        private final String nodeId;
        private final Instant detectedAt;
        private Instant recoveryStartAt;
        private String mechanism;

        OpenFailure(String runId, String nodeId, Instant detectedAt) {
            this.runId = runId;
            this.nodeId = nodeId;
            this.detectedAt = detectedAt;
        }

        void startRecovery(Instant at, String how) {
            if (recoveryStartAt == null) {
                recoveryStartAt = at;
                mechanism = how;
            }
        }

        FailureEvent close(Instant completedAt) {
            return new FailureEvent(runId, nodeId, detectedAt, recoveryStartAt, completedAt,
                    mechanism == null ? "Unknown" : mechanism);
        }

        FailureEvent abandon() {
            return new FailureEvent(runId, nodeId, detectedAt, recoveryStartAt, null,
                    mechanism == null ? "None" : mechanism);
        }
    }

    // ---------------------------------------------------------------- aggregates

    private RunStats runStats(List<RunRecord> runs) {
        long terminal = runs.stream().filter(RunRecord::isTerminal).count();
        long completed = count(runs, "COMPLETED");
        long withLimitations = count(runs, "COMPLETED_WITH_LIMITATIONS");
        long suspended = count(runs, "SUSPENDED") + count(runs, "AWAITING_CLARIFICATION");
        long failed = count(runs, "FAILED");
        long safeStopped = count(runs, "SAFE_STOPPED");
        long rejected = count(runs, "REJECTED");
        double successRate = terminal == 0 ? 0d : round((completed + withLimitations) / (double) terminal);
        return new RunStats(runs.size(), completed, withLimitations, suspended, failed, safeStopped, rejected, successRate);
    }

    private NodeStats nodeStats(List<TransitionRecord> transitions, int failureEventCount) {
        long attempts = countAction(transitions, AuditActions.NODE_STARTED);
        long retries = countAction(transitions, AuditActions.NODE_RETRY_SCHEDULED);
        long rollbacks = countAction(transitions, AuditActions.ROLLBACK_COMPLETED);
        long compensations = countAction(transitions, AuditActions.COMPENSATION_COMPLETED);
        double retryFrequency = attempts == 0 ? 0d : round(retries / (double) attempts);
        double recoveryFrequency = failureEventCount == 0 ? 0d
                : round((rollbacks + compensations) / (double) failureEventCount);
        return new NodeStats(attempts, retries, retryFrequency, rollbacks, compensations,
                failureEventCount, recoveryFrequency);
    }

    private LatencyStats latencyStats(List<RunRecord> runs) {
        List<Long> durations = runs.stream()
                .filter(RunRecord::isTerminal)
                .map(RunRecord::durationMs)
                .sorted()
                .toList();
        if (durations.isEmpty()) {
            return new LatencyStats(0, 0, "No terminal runs in the population.");
        }
        return new LatencyStats(percentile(durations, 50), percentile(durations, 95),
                "Wall-clock end to end; includes any human approval wait time.");
    }

    /** Nearest-rank percentile, which is the honest choice for the handful of runs a demo produces. */
    static long percentile(List<Long> sortedAscending, int percentile) {
        if (sortedAscending.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sortedAscending.size());
        return sortedAscending.get(Math.min(Math.max(rank, 1), sortedAscending.size()) - 1);
    }

    private static long count(List<RunRecord> runs, String outcome) {
        return runs.stream().filter(r -> outcome.equals(r.outcome())).count();
    }

    private static long countAction(List<TransitionRecord> transitions, String action) {
        return transitions.stream().filter(t -> action.equals(t.action())).count();
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
