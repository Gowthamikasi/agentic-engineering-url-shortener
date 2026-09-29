package com.example.urlshortener.policy;

import com.example.urlshortener.policy.PolicyEvaluator.CheckResult;
import com.example.urlshortener.policy.PolicyEvaluator.PolicyCheck;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The checks referenced by {@code policies/policy-set.v1.0.0.json}.
 *
 * <p>Each one answers from facts the run produced. A missing fact is reported as missing: a scan
 * that never ran is not a clean scan, and a rule that does not apply returns NOT_APPLICABLE so it
 * is counted separately from a pass.
 */
public final class DefaultPolicyChecks {

    // ---- fact keys, shared with the agents that populate them -------------------------------
    public static final String F_SCHEME_ALLOWLIST = "security.schemeAllowlistPresent";
    public static final String F_PRIVATE_HOSTS_BLOCKED = "security.privateHostsBlocked";
    public static final String F_SECRET_MASKING = "security.secretMaskingEnabled";
    public static final String F_DEP_SCAN_RAN = "security.dependencyScanRan";
    public static final String F_DEP_SCAN_HIGH = "security.highSeverityFindings";
    public static final String F_AUDIT_RETENTION = "compliance.auditRetainedForRunLifetime";
    public static final String F_CLICK_PII_FIELDS = "compliance.clickPiiFieldCount";
    public static final String F_LICENSE_REPORT = "licensing.reportPresent";
    public static final String F_LICENSE_DISALLOWED = "licensing.disallowedCount";
    public static final String F_CONTRACT_CHANGED = "changeControl.contractChanged";
    public static final String F_CONTRACT_VERSION_BUMPED = "changeControl.contractVersionBumped";
    public static final String F_CONTRACT_APPROVED = "changeControl.contractApproved";
    public static final String F_ARCHITECTURE_CHANGED = "changeControl.architectureChanged";
    public static final String F_ADR_IDS = "changeControl.adrIds";
    public static final String F_INPUT_KIND = "input.kind";
    public static final String F_IMPACT_ARTIFACT = "changeControl.impactAnalysisArtifactId";
    public static final String F_TDD_REQUIRED = "testing.tddRequired";
    public static final String F_TDD_EVIDENCE = "testing.tddEvidencePresent";
    public static final String F_TESTS_FAILED = "testing.failedTests";
    public static final String F_TESTS_RUN = "testing.testsRun";
    public static final String F_CONTRACT_TESTS_RUN = "testing.contractTestsRun";
    public static final String F_DEGRADED_NODES = "run.degradedNodes";

    private DefaultPolicyChecks() {
    }

    public static Map<String, PolicyCheck> registry() {
        Map<String, PolicyCheck> checks = new LinkedHashMap<>();

        checks.put("check.security.schemeAllowlist", ctx -> ctx.flag(F_SCHEME_ALLOWLIST)
                ? CheckResult.pass("UrlValidator restricts schemes to http and https.")
                : CheckResult.fail("No scheme allowlist is enforced on redirect targets."));

        checks.put("check.security.privateHostsBlocked", ctx -> ctx.flag(F_PRIVATE_HOSTS_BLOCKED)
                ? CheckResult.pass("Loopback, link-local, RFC1918 and ULA targets are refused at create time.")
                : CheckResult.fail("Redirect targets resolving to private addresses are not blocked."));

        checks.put("check.security.secretMasking", ctx -> ctx.flag(F_SECRET_MASKING)
                ? CheckResult.pass("API keys are hashed at rest and masked in logs.")
                : CheckResult.fail("Secret masking is not enabled for logs."));

        checks.put("check.security.dependencyScan", ctx -> {
            if (!ctx.flag(F_DEP_SCAN_RAN)) {
                return CheckResult.exceptionRequested(
                        "No dependency vulnerability scan was executed for this run.");
            }
            long high = ctx.number(F_DEP_SCAN_HIGH, -1);
            return high == 0
                    ? CheckResult.pass("Dependency scan reported no findings at CVSS 7 or above.")
                    : CheckResult.fail("Dependency scan reported " + high + " finding(s) at CVSS 7 or above.");
        });

        checks.put("check.compliance.auditRetention", ctx -> ctx.flag(F_AUDIT_RETENTION)
                ? CheckResult.pass("Audit rows are append-only and retained for the life of the run.")
                : CheckResult.fail("Audit retention for the run lifetime is not demonstrated."));

        checks.put("check.compliance.noPiiInClicks", ctx -> {
            long pii = ctx.number(F_CLICK_PII_FIELDS, -1);
            if (pii < 0) {
                return CheckResult.exceptionRequested("Click schema was not inspected for PII.");
            }
            return pii == 0
                    ? CheckResult.pass("Click rows store only code, timestamp, referer host and a UA class.")
                    : CheckResult.fail(pii + " PII-bearing field(s) found in the click schema.");
        });

        checks.put("check.licensing.allowlist", ctx -> {
            if (!ctx.flag(F_LICENSE_REPORT)) {
                return CheckResult.exceptionRequested("No dependency licence report was produced for this run.");
            }
            long disallowed = ctx.number(F_LICENSE_DISALLOWED, -1);
            return disallowed == 0
                    ? CheckResult.pass("All dependency licences are inside the allowlist.")
                    : CheckResult.fail(disallowed + " dependency licence(s) fall outside the allowlist.");
        });

        checks.put("check.changeControl.contractChange", ctx -> {
            if (!ctx.flag(F_CONTRACT_CHANGED)) {
                return CheckResult.notApplicable("This run does not change the API contract.");
            }
            if (!ctx.flag(F_CONTRACT_VERSION_BUMPED)) {
                return CheckResult.fail("The API contract changed without a version bump.");
            }
            return ctx.flag(F_CONTRACT_APPROVED)
                    ? CheckResult.pass("Contract change carries a version bump and an approval.")
                    : CheckResult.fail("The API contract changed but no approval is recorded.");
        });

        checks.put("check.changeControl.adrForArchitectureChange", ctx -> {
            if (!ctx.flag(F_ARCHITECTURE_CHANGED)) {
                return CheckResult.notApplicable("This run changes no architecture decision.");
            }
            String adrIds = ctx.text(F_ADR_IDS);
            return adrIds != null && !adrIds.isBlank()
                    ? CheckResult.pass("Architecture change is recorded in " + adrIds + ".")
                    : CheckResult.fail("An architecture decision changed with no ADR recorded.");
        });

        checks.put("check.changeControl.impactAnalysisForBrownfield", ctx -> {
            if (!"Brownfield".equalsIgnoreCase(ctx.text(F_INPUT_KIND))) {
                return CheckResult.notApplicable("Input is not classified as brownfield.");
            }
            String artifact = ctx.text(F_IMPACT_ARTIFACT);
            return artifact != null && !artifact.isBlank()
                    ? CheckResult.pass("Impact analysis artifact " + artifact + " is present.")
                    : CheckResult.fail("Brownfield change without an impact analysis artifact.");
        });

        checks.put("check.testing.tddEvidence", ctx -> {
            if (!ctx.flag(F_TDD_REQUIRED)) {
                return CheckResult.notApplicable("No task in this run is flagged as requiring TDD.");
            }
            // Absence of evidence and evidence of absence are different findings and get different
            // outcomes. Nothing captured is a gap a human has to decide on; captured evidence that
            // shows no failing run before the change is a violation, and blocks.
            if (ctx.missing(F_TDD_EVIDENCE)) {
                return CheckResult.exceptionRequested(
                        "TDD was required for one or more tasks, but this run captured no red-then-green evidence.");
            }
            return ctx.flag(F_TDD_EVIDENCE)
                    ? CheckResult.pass("A failing-test run precedes the implementing change for every TDD task.")
                    : CheckResult.fail("Captured evidence shows no failing test run before the implementing change.");
        });

        checks.put("check.testing.mandatoryTestsPass", ctx -> {
            if (ctx.missing(F_TESTS_RUN)) {
                return CheckResult.fail("No test results were reported for this run.");
            }
            long failed = ctx.number(F_TESTS_FAILED, -1);
            long run = ctx.number(F_TESTS_RUN, 0);
            return failed == 0
                    ? CheckResult.pass(run + " test(s) executed, 0 failed.")
                    : CheckResult.fail(failed + " of " + run + " test(s) failed.");
        });

        checks.put("check.testing.contractTestsPresent", ctx -> {
            long contractTests = ctx.number(F_CONTRACT_TESTS_RUN, 0);
            return contractTests > 0
                    ? CheckResult.pass(contractTests + " contract test(s) executed against the published API.")
                    : CheckResult.fail("No contract tests were executed for this run.");
        });

        return Map.copyOf(checks);
    }
}
