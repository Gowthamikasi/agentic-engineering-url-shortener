package com.example.urlshortener.orchestration.agents;

import com.example.urlshortener.orchestration.engine.StageAgent;
import com.example.urlshortener.orchestration.engine.StageContext;
import com.example.urlshortener.orchestration.engine.StageResult;
import com.example.urlshortener.orchestration.model.FailureClass;
import com.example.urlshortener.policy.DefaultPolicyChecks;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Impact analysis, decomposition, contract design, test planning and the change set. */
public final class DesignAgents {

    private DesignAgents() {
    }

    /**
     * Brownfield impact analysis: what the change touches and how it would be undone.
     *
     * <p>Runs before any code changes, because its output is what the approval gate shows.
     */
    public static class ImpactAnalysisAgent implements StageAgent {

        private final ObjectMapper json;
        private final CodebaseScanner scanner;

        public ImpactAnalysisAgent(ObjectMapper json, CodebaseScanner scanner) {
            this.json = json;
            this.scanner = scanner;
        }

        @Override
        public String agentType() {
            return "ImpactAnalysisAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            String text = context.inputText("text");
            CodebaseScanner.Impact impact = scanner.scan(text);

            ObjectNode report = json.createObjectNode();
            report.put("requirementId", context.inputText("requirementId"));
            report.put("method", "Term search over the repository main sources. Files are ranked by how "
                    + "many distinct requirement terms they mention. This is a search, not a semantic "
                    + "understanding of the code, and it is reported as such.");
            report.put("filesScanned", impact.filesScanned());
            ArrayNode terms = report.putArray("searchTerms");
            impact.terms().forEach(terms::add);

            ArrayNode modules = report.putArray("impactedModules");
            impact.modules().forEach(modules::add);

            ArrayNode files = report.putArray("impactedFiles");
            for (CodebaseScanner.Match match : impact.matches()) {
                ObjectNode f = files.addObject();
                f.put("path", match.path());
                f.put("module", match.module());
                f.put("kind", match.type());
                f.put("score", match.score());
                ArrayNode matched = f.putArray("matchedTerms");
                match.matchedTerms().forEach(matched::add);
            }

            ArrayNode api = report.putArray("impactedApiSurface");
            impact.apiSurface().forEach(api::add);
            ArrayNode data = report.putArray("impactedDataFlows");
            impact.dataFlows().forEach(data::add);

            // Report an empty scan as empty. A plausible-looking guess would read exactly like a
            // real finding at the approval gate.
            if (impact.isEmpty()) {
                report.put("finding", "No source file matched enough requirement terms to be reported "
                        + "as impacted. The change may be new work, or the requirement may not use the "
                        + "vocabulary the code uses. A human must determine the scope at the gate.");
                report.put("confidence", "none");
            } else {
                report.put("finding", impact.matches().size() + " file(s) across "
                        + impact.modules().size() + " module(s) mention this requirement's terms.");
                report.put("confidence", impact.matches().get(0).score() >= 3 ? "medium" : "low");
            }

            boolean touchesApi = !impact.apiSurface().isEmpty();
            boolean touchesData = !impact.dataFlows().isEmpty();
            report.put("apiContractImpact", touchesApi
                    ? "possible: matched files expose HTTP endpoints; the contract node decides the version impact"
                    : "none detected: no matched file exposes an HTTP endpoint");
            report.put("dataImpact", touchesData
                    ? "possible: matched files include schema or entity definitions"
                    : "none detected: no schema or entity file matched");

            // Flagging this lets the change-control policy insist on an ADR, rather than relying
            // on someone remembering to write one.
            boolean architectureDecisionChanged = mentionsDecidedConcern(text);
            report.put("architectureDecisionChanged", architectureDecisionChanged);
            if (architectureDecisionChanged) {
                report.put("supersededAdr", "ADR-004");
                report.put("newAdr", "ADR-017");
            }

            report.put("rollout", touchesData
                    ? "requires a migration review: matched files include schema definitions"
                    : "configuration flag; no schema file matched, so no data migration is implied");
            report.put("rollback", touchesData
                    ? "not a pure flag flip: data would be mutated, so rollback needs a restore path"
                    : "flag flip: no data is mutated, so the previous behaviour is one setting away");

            StageResult.Builder result = StageResult.success(
                            impact.isEmpty()
                                    ? "No impacted file identified from " + impact.filesScanned() + " scanned"
                                    : impact.matches().size() + " impacted file(s) across "
                                      + impact.modules().size() + " module(s), from "
                                      + impact.filesScanned() + " scanned")
                    .artifact("ImpactAnalysis", report.toString(), context.upstreamArtifactIds())
                    .fact(DefaultPolicyChecks.F_IMPACT_ARTIFACT, context.nodeId() + ":ImpactAnalysis:v1")
                    .fact("impact.moduleCount", impact.modules().size())
                    .fact("impact.fileCount", impact.matches().size());

            if (architectureDecisionChanged) {
                result.fact(DefaultPolicyChecks.F_ARCHITECTURE_CHANGED, true)
                        .fact(DefaultPolicyChecks.F_ADR_IDS, "ADR-017 (supersedes ADR-004)");
            }
            if (impact.isEmpty()) {
                result.assumption("Impact analysis found no matching source file; scope must be "
                        + "confirmed by a human at the impact-approval gate.");
            }
            return result.build();
        }

        /** True when the requirement names something an accepted ADR already decided. */
        static boolean mentionsDecidedConcern(String text) {
            if (text == null) {
                return false;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            return lower.contains("alphabet") || lower.contains("base58") || lower.contains("base62")
                    || lower.contains("short code") || lower.contains("short-code");
        }
    }

    /** Breaks the requirement into ordered tasks, flagging the ones that need tests first. */
    public static class DecomposeAgent implements StageAgent {

        private final ObjectMapper json;

        public DecomposeAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "DecomposeAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            String kind = Optional.ofNullable(context.inputText("kind")).orElse("Unclassified");
            ObjectNode plan = json.createObjectNode();
            plan.put("requirementId", context.inputText("requirementId"));
            plan.put("definitionVersion", context.definitionVersion());
            ArrayNode tasks = plan.putArray("tasks");

            if ("Brownfield".equalsIgnoreCase(kind)) {
                task(tasks, "T-B1", "Capture a regression baseline for codes minted before the change",
                        List.of(), true);
                task(tasks, "T-B2", "Write failing tests for the new alphabet", List.of("T-B1"), true);
                task(tasks, "T-B3", "Introduce the alphabet strategy", List.of("T-B2"), false);
                task(tasks, "T-B4", "Widen the lookup validator to accept both alphabets", List.of("T-B3"), true);
                task(tasks, "T-B5", "Record ADR-017 superseding ADR-004", List.of("T-B3"), false);
            } else {
                task(tasks, "T-A1", "Update the API contract for the additive field", List.of(), false);
                task(tasks, "T-A2", "Write the failing acceptance tests", List.of("T-A1"), true);
                task(tasks, "T-A3", "Implement the domain rule", List.of("T-A2"), true);
                task(tasks, "T-A4", "Wire the rule through the API layer", List.of("T-A3"), true);
                task(tasks, "T-A5", "Update documentation and traceability", List.of("T-A4"), false);
            }

            long tddTasks = java.util.stream.StreamSupport
                    .stream(tasks.spliterator(), false)
                    .filter(t -> t.path("tddRequired").asBoolean())
                    .count();

            return StageResult.success("Decomposed into " + tasks.size() + " task(s), " + tddTasks + " TDD-flagged")
                    .artifact("Decomposition", plan.toString(), context.upstreamArtifactIds())
                    .fact(DefaultPolicyChecks.F_TDD_REQUIRED, tddTasks > 0)
                    .fact("decomposition.taskCount", tasks.size())
                    .build();
        }

        private void task(ArrayNode tasks, String id, String title, List<String> dependsOn, boolean tddRequired) {
            ObjectNode node = tasks.addObject();
            node.put("id", id);
            node.put("title", title);
            node.put("tddRequired", tddRequired);
            ArrayNode deps = node.putArray("dependsOn");
            dependsOn.forEach(deps::add);
        }
    }

    /**
     * Decides the API contract impact and the version change that follows.
     *
     * <p>The version rule is mechanical: an additive optional field is a minor bump, a removal
     * or a type change is a major one behind a new path prefix.
     */
    public static class ContractAgent implements StageAgent {

        private final ObjectMapper json;

        public ContractAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "ContractAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            String text = String.valueOf(context.inputText("text")).toLowerCase(Locale.ROOT);
            boolean additiveField = text.contains("expiresat") || text.contains("optional");
            boolean noWireChange = text.contains("base58") || text.contains("alphabet");

            ObjectNode contract = json.createObjectNode();
            contract.put("currentVersion", "1.0.0");

            if (noWireChange) {
                contract.put("changed", false);
                contract.put("targetVersion", "1.0.0");
                contract.put("compatibility", "no wire change; recorded as a no-impact determination");
            } else if (additiveField) {
                contract.put("changed", true);
                contract.put("targetVersion", "1.1.0");
                contract.put("compatibility", "backward compatible: an optional field is added, existing clients unaffected");
                contract.putArray("affectedConsumers");
            } else {
                contract.put("changed", true);
                contract.put("targetVersion", "1.1.0");
                contract.put("compatibility", "assumed additive; flagged for review at the release gate");
            }
            contract.put("owner", "application-plane");

            boolean changed = contract.path("changed").asBoolean();
            return StageResult.success(changed
                            ? "Contract change to " + contract.path("targetVersion").asText()
                            : "No contract change required")
                    .artifact("ApiContract", contract.toString(), context.upstreamArtifactIds())
                    .fact(DefaultPolicyChecks.F_CONTRACT_CHANGED, changed)
                    .fact(DefaultPolicyChecks.F_CONTRACT_VERSION_BUMPED,
                            changed && !contract.path("targetVersion").asText().equals("1.0.0"))
                    .fact(DefaultPolicyChecks.F_CONTRACT_APPROVED, changed)
                    .build();
        }
    }

    /** Derives the acceptance tests each task has to satisfy. */
    public static class TestPlanAgent implements StageAgent {

        private final ObjectMapper json;

        public TestPlanAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "TestPlanAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            String kind = Optional.ofNullable(context.inputText("kind")).orElse("Unclassified");
            ObjectNode plan = json.createObjectNode();
            ArrayNode cases = plan.putArray("cases");

            if ("Brownfield".equalsIgnoreCase(kind)) {
                testCase(cases, "TC-B1", "a code minted under the previous alphabet still resolves", "regression");
                testCase(cases, "TC-B2", "newly minted codes contain no ambiguous glyphs", "unit");
                testCase(cases, "TC-B3", "lookup accepts both alphabets", "unit");
            } else {
                testCase(cases, "TC-A1", "creating with a future expiresAt returns 201 and echoes it", "contract");
                testCase(cases, "TC-A2", "following an expired link returns 410 with a problem body", "contract");
                testCase(cases, "TC-A3", "an expiresAt in the past returns 400", "contract");
                testCase(cases, "TC-A4", "stats still report clicks after expiry", "contract");
            }
            plan.put("strategy", "red-green-refactor for every task flagged tddRequired");

            return StageResult.success("Test plan derived: " + cases.size() + " acceptance case(s)")
                    .artifact("TestPlan", plan.toString(), context.upstreamArtifactIds())
                    .fact("testing.plannedCases", cases.size())
                    .build();
        }

        private void testCase(ArrayNode cases, String id, String expectation, String level) {
            ObjectNode node = cases.addObject();
            node.put("id", id);
            node.put("expectation", expectation);
            node.put("level", level);
        }
    }

    /**
     * Records the change set the decomposition and contract call for.
     *
     * <p>It does not generate code (ASM-001, EXC-004), and the artifact it produces says so.
     */
    public static class ImplementAgent implements StageAgent {

        private final ObjectMapper json;

        public ImplementAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "ImplementAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            ObjectNode changeSet = json.createObjectNode();
            changeSet.put("requirementId", context.inputText("requirementId"));
            changeSet.put("attempt", context.attempt());
            changeSet.put("reversible", true);
            changeSet.put("reversalMethod", "revert the recorded change set; no data migration was performed");
            changeSet.put("note", "Deterministic stage executor (ASM-001): records the change set, "
                    + "does not synthesise source code.");
            ArrayNode inputs = changeSet.putArray("derivedFrom");
            context.upstreamArtifactIds().forEach(inputs::add);

            StageResult.Builder result = StageResult.success(
                            "Change set recorded from " + context.upstreamArtifactIds().size()
                                    + " upstream artifact(s)")
                    .artifact("ChangeSet", changeSet.toString(), context.upstreamArtifactIds())
                    .fact("implement.changeSetRecorded", true);

            // Only report TDD evidence when the run supplied some. This agent does not write code,
            // so it cannot have watched a test go red then green; left unset, the policy set asks a
            // human instead.
            Object tddEvidence = context.input().get("tddEvidence");
            if (tddEvidence instanceof Map<?, ?> evidence) {
                boolean redThenGreen = Boolean.parseBoolean(String.valueOf(evidence.get("redThenGreen")));
                result.fact(DefaultPolicyChecks.F_TDD_EVIDENCE, redThenGreen);
                changeSet.put("tddEvidenceRef", String.valueOf(evidence.get("ref")));
            }
            return result.build();
        }
    }

    /** Never dispatched. Gate nodes are held by the engine and settled by a human decision. */
    public static class HumanGateAgent implements StageAgent {

        @Override
        public String agentType() {
            return "HumanGateAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            return StageResult.failure(FailureClass.PERMANENT,
                    "A human gate must not be executed by an agent. Node '" + context.nodeId()
                            + "' was dispatched, which means the gate wiring is wrong.").build();
        }
    }
}
