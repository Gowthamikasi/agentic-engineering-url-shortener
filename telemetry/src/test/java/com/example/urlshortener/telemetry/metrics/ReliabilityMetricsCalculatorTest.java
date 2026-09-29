package com.example.urlshortener.telemetry.metrics;

import com.example.urlshortener.telemetry.audit.AuditActions;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.ReliabilityReport;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.RunRecord;
import com.example.urlshortener.telemetry.metrics.ReliabilityModel.TransitionRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReliabilityMetricsCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-09-28T14:00:00Z");
    private final ReliabilityMetricsCalculator calculator = new ReliabilityMetricsCalculator();

    private final List<TransitionRecord> journal = new ArrayList<>();
    private long seq = 0;

    private void event(String runId, String nodeId, String action, long secondsFromT0) {
        journal.add(new TransitionRecord(runId, ++seq, nodeId, action, T0.plusSeconds(secondsFromT0), null));
    }

    @Test
    void mttr_averages_only_recovered_failures() {
        // run A: failure at +10s, retry at +11s, success at +14s  -> recovered in 4s
        event("runA", "contract-tests", AuditActions.NODE_STARTED, 8);
        event("runA", "contract-tests", AuditActions.NODE_FAILED, 10);
        event("runA", "contract-tests", AuditActions.NODE_RETRY_SCHEDULED, 11);
        event("runA", "contract-tests", AuditActions.NODE_SUCCEEDED, 14);
        // run B: failure at +20s, rollback at +21s, success at +40s -> recovered in 20s
        event("runB", "regression", AuditActions.NODE_STARTED, 18);
        event("runB", "regression", AuditActions.NODE_FAILED, 20);
        event("runB", "regression", AuditActions.ROLLBACK_STARTED, 21);
        event("runB", "regression", AuditActions.ROLLBACK_COMPLETED, 22);
        event("runB", "regression", AuditActions.NODE_SUCCEEDED, 40);

        ReliabilityReport report = calculator.compute(List.of(), journal, "test-window");

        assertThat(report.recovery().recoveredCount()).isEqualTo(2);
        assertThat(report.recovery().totalRecoveryDurationMs()).isEqualTo(24_000);
        assertThat(report.recovery().mttrMs()).isEqualTo(12_000);
        assertThat(report.recovery().recoveredFailureEvents())
                .extracting(ReliabilityModel.FailureEvent::mechanism)
                .containsExactly("Retry", "Rollback");
    }

    @Test
    void an_unrecovered_failure_is_reported_separately_and_excluded_from_the_mttr_denominator() {
        event("runA", "unit-tests", AuditActions.NODE_FAILED, 10);
        event("runA", "unit-tests", AuditActions.NODE_RETRY_SCHEDULED, 11);
        event("runA", "unit-tests", AuditActions.NODE_SUCCEEDED, 15);
        event("runB", "security-scan", AuditActions.NODE_FAILED, 30);
        event("runB", "security-scan", AuditActions.SAFE_STOP, 31);

        ReliabilityReport report = calculator.compute(List.of(), journal, "test-window");

        assertThat(report.recovery().recoveredCount()).isEqualTo(1);
        assertThat(report.recovery().unrecoveredCount()).isEqualTo(1);
        // 5s, not the mean of 5s and "never"
        assertThat(report.recovery().mttrMs()).isEqualTo(5_000);
        assertThat(report.recovery().unrecoveredFailureEvents())
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.nodeId()).isEqualTo("security-scan");
                    assertThat(f.recovered()).isFalse();
                });
    }

    @Test
    void mttr_is_null_rather_than_zero_when_nothing_was_recovered() {
        event("runA", "implement", AuditActions.NODE_FAILED, 5);

        ReliabilityReport report = calculator.compute(List.of(), journal, "test-window");

        assertThat(report.recovery().mttrMs()).isNull();
        assertThat(report.recovery().recoveredCount()).isZero();
    }

    @Test
    void a_human_resume_counts_as_the_recovery_mechanism_for_open_failures_in_that_run() {
        event("runC", "implement", AuditActions.NODE_FAILED, 10);
        event("runC", null, AuditActions.WORKFLOW_RESUMED, 100);
        event("runC", "implement", AuditActions.NODE_SUCCEEDED, 120);

        ReliabilityReport report = calculator.compute(List.of(), journal, "test-window");

        assertThat(report.recovery().recoveredFailureEvents()).singleElement().satisfies(f -> {
            assertThat(f.mechanism()).isEqualTo("HumanResume");
            assertThat(f.durationMs()).isEqualTo(110_000);
        });
    }

    @Test
    void run_and_node_aggregates_come_from_the_journal_and_the_run_records() {
        event("runA", "a", AuditActions.NODE_STARTED, 1);
        event("runA", "b", AuditActions.NODE_STARTED, 1);
        event("runA", "b", AuditActions.NODE_FAILED, 2);
        event("runA", "b", AuditActions.NODE_RETRY_SCHEDULED, 2);
        event("runA", "b", AuditActions.NODE_STARTED, 3);
        event("runA", "b", AuditActions.NODE_SUCCEEDED, 4);

        List<RunRecord> runs = List.of(
                new RunRecord("runA", "COMPLETED", T0, T0.plusSeconds(10)),
                new RunRecord("runB", "SUSPENDED", T0, null),
                new RunRecord("runC", "FAILED", T0, T0.plusSeconds(30)));

        ReliabilityReport report = calculator.compute(runs, journal, "test-window");

        assertThat(report.runs().total()).isEqualTo(3);
        assertThat(report.runs().completed()).isEqualTo(1);
        assertThat(report.runs().failed()).isEqualTo(1);
        assertThat(report.runs().suspended()).isEqualTo(1);
        // suspended runs are not terminal, so the denominator is 2, not 3
        assertThat(report.runs().successRate()).isEqualTo(0.5);
        assertThat(report.nodes().attempts()).isEqualTo(3);
        assertThat(report.nodes().retries()).isEqualTo(1);
        assertThat(report.latency().p50Ms()).isEqualTo(10_000);
        assertThat(report.latency().p95Ms()).isEqualTo(30_000);
    }

    @Test
    void every_report_is_labelled_as_demonstration_data() {
        ReliabilityReport report = calculator.compute(List.of(), List.of(), "test-window");

        assertThat(report.dataClass()).isEqualTo("DEMONSTRATION");
        assertThat(report.note()).contains("not production statistics");
    }

    @Test
    void percentiles_use_nearest_rank() {
        List<Long> values = List.of(10L, 20L, 30L, 40L);

        assertThat(ReliabilityMetricsCalculator.percentile(values, 50)).isEqualTo(20L);
        assertThat(ReliabilityMetricsCalculator.percentile(values, 95)).isEqualTo(40L);
        assertThat(ReliabilityMetricsCalculator.percentile(List.of(), 50)).isZero();
    }
}
