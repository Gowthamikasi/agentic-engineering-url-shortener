package com.example.urlshortener.telemetry.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * The read model the reliability calculator works from.
 *
 * <p>These types let telemetry compute MTTR without depending on the engine: the control plane
 * projects its journal into them. Everything here comes from stored rows.
 */
public final class ReliabilityModel {

    private ReliabilityModel() {
    }

    /** Terminal (or in-flight) summary of one workflow run. */
    public record RunRecord(String runId, String outcome, Instant createdAt, Instant terminalAt) {

        public boolean isTerminal() {
            return terminalAt != null;
        }

        public long durationMs() {
            return terminalAt == null ? 0L : terminalAt.toEpochMilli() - createdAt.toEpochMilli();
        }
    }

    /** One journal row, flattened to what the calculator needs. */
    public record TransitionRecord(String runId, long seq, String nodeId, String action, Instant timestamp, String detail) {
    }

    /** A failure that was detected and, possibly, recovered from. */
    public record FailureEvent(
            String runId,
            String nodeId,
            Instant failureDetectedAt,
            Instant recoveryStartAt,
            Instant recoveryCompleteAt,
            String mechanism) {

        /** Annotated because Jackson maps record components only; a derived accessor needs asking for. */
        @JsonProperty("recovered")
        public boolean recovered() {
            return recoveryCompleteAt != null;
        }

        /** Detection to recovery, which is the interval MTTR averages. */
        @JsonProperty("durationMs")
        public long durationMs() {
            return recovered() ? recoveryCompleteAt.toEpochMilli() - failureDetectedAt.toEpochMilli() : 0L;
        }
    }

    public record RunStats(long total, long completed, long completedWithLimitations, long suspended,
                           long failed, long safeStopped, long rejected, double successRate) {
    }

    public record NodeStats(long attempts, long retries, double retryFrequency,
                            long rollbacks, long compensations, long failures,
                            double rollbackOrCompensationFrequency) {
    }

    public record LatencyStats(long p50Ms, long p95Ms, String note) {
    }

    public record RecoveryStats(List<FailureEvent> recoveredFailureEvents, long recoveredCount,
                                long totalRecoveryDurationMs, Long mttrMs,
                                List<FailureEvent> unrecoveredFailureEvents, long unrecoveredCount) {
    }

    public record Population(List<String> runIds, String window, List<String> exclusions) {
    }

    /** The whole report, shaped for the {@code /api/v1/metrics/reliability} response. */
    public record ReliabilityReport(String dataClass, String note, Population population,
                                    RunStats runs, NodeStats nodes, LatencyStats latency, RecoveryStats recovery) {

        /** REQ-D-013: demonstration measurements must never be presented as production statistics. */
        public static final String DATA_CLASS = "DEMONSTRATION";
        public static final String NOTE =
                "Synthetic demonstration runs executed by scripts/demo-scenarios.sh; not production statistics.";
    }
}
