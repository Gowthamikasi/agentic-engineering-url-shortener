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

/** Impact analysis, decomposition, contract design and test planning. */
public final class DesignAgents {

    private DesignAgents() {
    }

    /**
     * Brownfield impact analysis: what the change touches, and how it would be undone.
     *
     * <p>This runs <em>before</em> any code is changed and its output is what the impact-approval
     * gate shows a human. Producing it afterwards would make the gate decorative.
     */
    public static class ImpactAnalysisAgent implements StageAgent {

        private final ObjectMapper json;

        public ImpactAnalysisAgent(ObjectMapper json) {
            this.json = json;
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

            String text = String.valueOf(context.inputText("text")).toLowerCase(Locale.ROOT);
            boolean alphabetChange = text.contains("base58") || text.contains("alphabet");

            ObjectNode impact = json.createObjectNode();
            impact.put("requirementId", context.inputText("requirementId"));

            ArrayNode modules = impact.putArray("impactedModules");
            ArrayNode interfaces = impact.putArray("impactedInterfaces");
            ArrayNode dataFlows = impact.putArray("impactedDataFlows");
            ArrayNode tests = impact.putArray("requiredTests");

            if (alphabetChange) {
                modules.add("url-shortener-domain/ShortCodeGenerator");
                modules.add("url-shortener-domain/ShortCodeValidator");
                modules.add("url-shortener-api/ApplicationPlaneConfig");
                interfaces.add("none public: the alphabet moves from a constant to an injected strategy");
                dataFlows.add("existing rows are untouched; the compatibility risk is validation on read");
                tests.add("regression: links minted before the change still resolve");
                tests.add("generator mints only from the new alphabet");
                tests.add("lookup validator accepts both alphabets");
                impact.put("apiContractImpact", "none — no wire format changes; recorded as a no-impact determination");
                impact.put("architectureDecisionChanged", true);
                impact.put("supersededAdr", "ADR-004");
                impact.put("newAdr", "ADR-017");
                impact.put("rollout", "configuration flag urlshortener.short-code.alphabet");
                impact.put("rollback", "flip the flag back; no data was mutated, so this is a true rollback");
            } else {
                modules.add("undetermined — no module signature matched the requirement text");
                impact.put("apiContractImpact", "unknown");
                impact.put("architectureDecisionChanged", false);
                impact.put("rollout", "to be determined at the approval gate");
                impact.put("rollback", "to be determined at the approval gate");
            }

            StageResult.Builder result = StageResult.success(
                            "Impact analysis complete: " + modules.size() + " module(s) affected")
                    .artifact("ImpactAnalysis", impact.toString(), context.upstreamArtifactIds())
                    .fact(DefaultPolicyChecks.F_IMPACT_ARTIFACT, context.nodeId() + ":ImpactAnalysis:v1");

            if (alphabetChange) {
                result.fact(DefaultPolicyChecks.F_ARCHITECTURE_CHANGED, true)
                        .fact(DefaultPolicyChecks.F_ADR_IDS, "ADR-017 (supersedes ADR-004)");
            }
            return result.build();
        }
    }

    /** Breaks the requirement into ordered tasks, flagging which ones must be driven by tests. */
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
     * Decides the API contract impact and the version change that follows from it.
     *
     * <p>The version rule is mechanical on purpose: an additive optional field is a minor bump, a
     * removal or a type change is a major bump behind a new path prefix. Leaving it to judgement is
     * how consumers get broken by a change someone considered "small".
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

    /** Derives the acceptance tests each task must satisfy. */
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
     * Applies the change set.
     *
     * <p>ASM-001 / EXC-004: this agent does not generate code. It records the change set that the
     * decomposition and contract call for, as a rollbackable artifact. Claiming otherwise would be
     * the one kind of dishonesty this whole exercise is built to avoid.
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

            // The TDD fact is only contributed when the run was given real evidence to point at.
            // Left unset, the policy set raises an exception request that a human must decide —
            // which is the correct answer, because this agent does not write code and therefore
            // cannot have watched a test go red and then green.
            Object tddEvidence = context.input().get("tddEvidence");
            if (tddEvidence instanceof Map<?, ?> evidence) {
                boolean redThenGreen = Boolean.parseBoolean(String.valueOf(evidence.get("redThenGreen")));
                result.fact(DefaultPolicyChecks.F_TDD_EVIDENCE, redThenGreen);
                changeSet.put("tddEvidenceRef", String.valueOf(evidence.get("ref")));
            }
            return result.build();
        }
    }

    /** Never dispatched: gate nodes are held by the engine and settled by a human decision. */
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
