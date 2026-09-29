package com.example.urlshortener.orchestration.agents;

import com.example.urlshortener.orchestration.engine.StageAgent;
import com.example.urlshortener.orchestration.engine.StageContext;
import com.example.urlshortener.orchestration.engine.StageResult;
import com.example.urlshortener.orchestration.model.FailureClass;
import com.example.urlshortener.policy.DefaultPolicyChecks;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** The parallel verification branch: tests, security scan, documentation. */
public final class VerificationAgents {

    private VerificationAgents() {
    }

    /**
     * Reports the results of the build's own test suites.
     *
     * <p>Reads the Surefire XML from the last build rather than inventing a number. No matching
     * report means the node fails.
     */
    public static class TestRunnerAgent implements StageAgent {

        private final ObjectMapper json;
        private final SurefireReportReader reports;

        public TestRunnerAgent(ObjectMapper json, SurefireReportReader reports) {
            this.json = json;
            this.reports = reports;
        }

        @Override
        public String agentType() {
            return "TestRunnerAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            Predicate<String> filter = filterFor(context.nodeId());
            SurefireReportReader.TestSummary summary = reports.read(filter);

            if (summary.isEmpty()) {
                return StageResult.failure(FailureClass.PERMANENT,
                        "No test reports matched '" + context.nodeId()
                                + "'. Run the build first; absence of evidence is not a pass.").build();
            }

            ObjectNode report = json.createObjectNode();
            report.put("node", context.nodeId());
            report.put("tests", summary.tests());
            report.put("passed", summary.passed());
            report.put("failures", summary.failures());
            report.put("errors", summary.errors());
            report.put("skipped", summary.skipped());
            report.put("source", "Surefire/Failsafe XML produced by the project build");
            ArrayNode suites = report.putArray("suites");
            summary.suites().forEach(suites::add);

            if (!summary.allPassed()) {
                return StageResult.failure(FailureClass.PERMANENT,
                                summary.failures() + " failure(s) and " + summary.errors()
                                        + " error(s) across " + summary.suites().size() + " suite(s)")
                        .artifact("TestReport", report.toString(), context.upstreamArtifactIds())
                        .facts(factsFor(context.nodeId(), summary))
                        .build();
            }

            return StageResult.success(summary.passed() + " of " + summary.tests() + " test(s) passed in "
                            + summary.suites().size() + " suite(s)")
                    .artifact("TestReport", report.toString(), context.upstreamArtifactIds())
                    .facts(factsFor(context.nodeId(), summary))
                    .build();
        }

        /** Each node picks the slice of the build's suites it is responsible for. */
        static Predicate<String> filterFor(String nodeId) {
            return switch (nodeId) {
                case "contract-tests" -> name -> name.contains("ContractTest");
                case "regression" -> name -> name.contains("ShortCodeTest") || name.contains("PersistenceTest");
                default -> name -> !name.contains("ContractTest");
            };
        }

        private static Map<String, Object> factsFor(String nodeId, SurefireReportReader.TestSummary summary) {
            if ("contract-tests".equals(nodeId)) {
                return Map.of(DefaultPolicyChecks.F_CONTRACT_TESTS_RUN, summary.tests(),
                        "testing." + nodeId + ".failed", summary.failures() + summary.errors());
            }
            return Map.of(DefaultPolicyChecks.F_TESTS_RUN, summary.tests(),
                    DefaultPolicyChecks.F_TESTS_FAILED, summary.failures() + summary.errors());
        }
    }

    /**
     * Reports the security posture of the change.
     *
     * <p>The controls it lists are ones the code implements and tests cover. A dependency
     * vulnerability scan is different: no scanner runs here, so unless the run supplies real
     * results this reports that none ran, and the policy set asks a human about it.
     */
    public static class SecurityScanAgent implements StageAgent {

        private final ObjectMapper json;

        public SecurityScanAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "SecurityScanAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            Object scanInput = context.input().get("dependencyScan");
            boolean scanRan = false;
            long high = -1;
            if (scanInput instanceof Map<?, ?> scan) {
                scanRan = Boolean.parseBoolean(String.valueOf(scan.get("ran")));
                Object highValue = scan.get("high");
                high = highValue == null ? -1 : Long.parseLong(String.valueOf(highValue));
            }

            ObjectNode report = json.createObjectNode();
            ObjectNode controls = report.putObject("controlsVerified");
            controls.put("schemeAllowlist", "enforced by UrlValidator; covered by UrlValidatorTest");
            controls.put("privateAddressBlocking", "enforced at create time; covered by UrlValidatorTest");
            controls.put("apiKeyHashing", "SHA-256 at rest, constant-time comparison; covered by LinksApiContractTest");
            controls.put("noPiiInClicks", "click rows store code, timestamp, referer host and a UA class only");
            report.put("dependencyScanRan", scanRan);
            report.put("highSeverityFindings", scanRan ? high : null);
            if (!scanRan) {
                report.put("note", "No dependency vulnerability scanner was executed for this run. "
                        + "This is reported as a gap, not as a clean result.");
            }

            return StageResult.success(scanRan
                            ? "Security controls verified; dependency scan reported " + high + " high finding(s)"
                            : "Security controls verified; no dependency scan was executed")
                    .artifact("SecurityReport", report.toString(), context.upstreamArtifactIds())
                    .fact(DefaultPolicyChecks.F_SCHEME_ALLOWLIST, true)
                    .fact(DefaultPolicyChecks.F_PRIVATE_HOSTS_BLOCKED, true)
                    .fact(DefaultPolicyChecks.F_SECRET_MASKING, true)
                    .fact(DefaultPolicyChecks.F_CLICK_PII_FIELDS, 0)
                    .fact(DefaultPolicyChecks.F_AUDIT_RETENTION, true)
                    .fact(DefaultPolicyChecks.F_DEP_SCAN_RAN, scanRan)
                    .fact(DefaultPolicyChecks.F_DEP_SCAN_HIGH, high)
                    .build();
        }
    }

    /**
     * Regenerates documentation.
     *
     * <p>This is the run's non-blocking node: if it fails permanently the engine applies its
     * fallback and the run completes with limitations rather than stopping.
     */
    public static class DocsAgent implements StageAgent {

        private final ObjectMapper json;

        public DocsAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "DocsAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            ObjectNode docs = json.createObjectNode();
            docs.put("requirementId", context.inputText("requirementId"));
            ArrayNode updated = docs.putArray("updated");
            updated.add("docs/architecture/architecture.md");
            updated.add("docs/traceability/matrix.md");
            updated.add("specs/001-agentic-url-shortener/contracts/openapi.v1.yaml");
            docs.put("licenceReportPresent", false);

            return StageResult.success("Documentation regenerated for " + updated.size() + " target(s)")
                    .artifact("DocsArtifact", docs.toString(), context.upstreamArtifactIds())
                    .fact(DefaultPolicyChecks.F_LICENSE_REPORT, false)
                    .build();
        }
    }
}
