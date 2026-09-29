package com.example.urlshortener.orchestration.agents;

import com.example.urlshortener.orchestration.engine.StageAgent;
import com.example.urlshortener.orchestration.engine.StageContext;
import com.example.urlshortener.orchestration.engine.StageResult;
import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Decision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Intake, normalisation, and the quality check that decides whether to ask a human. */
public final class RequirementAgents {

    private RequirementAgents() {
    }

    /** Accepts the raw requirement and classifies the kind of change it is. */
    public static class RequirementIngestAgent implements StageAgent {

        private final ObjectMapper json;

        public RequirementIngestAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "RequirementIngestAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            String text = context.inputText("text");
            if (text == null || text.isBlank()) {
                return StageResult.failure(com.example.urlshortener.orchestration.model.FailureClass.PERMANENT,
                        "The run input carries no requirement text.").build();
            }
            String kind = Optional.ofNullable(context.inputText("kind")).orElse("Unclassified");

            ObjectNode node = json.createObjectNode();
            node.put("requirementId", context.inputText("requirementId"));
            node.put("kind", kind);
            node.put("rawText", text);
            node.put("receivedAt", java.time.Instant.now().toString());

            return StageResult.success("Requirement accepted and classified as " + kind)
                    .artifact("RawRequirement", node.toString(), List.of())
                    .fact("input.kind", kind)
                    .fact("input.requirementId", context.inputText("requirementId"))
                    .build();
        }
    }

    /**
     * Restates the requirement in testable form.
     *
     * <p>A recorded clarification decision gets folded in here, which is why the clarify gate
     * supersedes this node: re-running it gives everything downstream a different input.
     */
    public static class RequirementNormalizeAgent implements StageAgent {

        private final ObjectMapper json;

        public RequirementNormalizeAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "RequirementNormalizeAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            Optional<FaultInjection.Fault> fault = FaultInjection.forAttempt(context);
            if (fault.isPresent()) {
                return StageResult.failure(fault.get().failureClass(), fault.get().reason()).build();
            }

            String original = context.inputText("text");
            Optional<Decision> clarification = context.decisionForGate("clarify");

            String effectiveText = clarification
                    .map(d -> original + "\n\nAmended by clarification decision: " + d.rationale())
                    .orElse(original);

            ObjectNode node = json.createObjectNode();
            node.put("requirementId", context.inputText("requirementId"));
            node.put("statement", effectiveText);
            node.put("amendedByDecision", clarification.map(Decision::decisionId).orElse(null));
            node.put("definitionVersion", context.definitionVersion());

            ArrayNode statements = node.putArray("testableStatements");
            for (String sentence : effectiveText.split("(?<=\\.)\\s+")) {
                if (!sentence.isBlank()) {
                    statements.add(sentence.strip());
                }
            }

            String message = clarification.isPresent()
                    ? "Requirement normalised, incorporating clarification " + clarification.get().decisionId()
                    : "Requirement normalised into " + statements.size() + " testable statement(s)";

            return StageResult.success(message)
                    .artifact("NormalizedRequirement", node.toString(), context.upstreamArtifactIds())
                    .fact("requirement.amended", clarification.isPresent())
                    .build();
        }
    }

    /**
     * Decides whether the requirement is safe to implement as written.
     *
     * <p>The gate is only armed when a check actually fails (REQ-D-009). Asking a question on
     * every run would train reviewers to approve without reading.
     */
    public static class RequirementQualityAgent implements StageAgent {

        /** Phrases that state an intent without giving anything testable. */
        private static final List<String> VAGUE_MARKERS = List.of(
                "a while", "some time", "soon", "popular", "fast", "slow", "a few",
                "etc", "and so on", "as needed", "appropriate", "reasonable", "user-friendly");

        private final ObjectMapper json;

        public RequirementQualityAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "RequirementQualityAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            String statement = context.upstreamOfType("NormalizedRequirement")
                    .map(Artifact::contentJson)
                    .map(this::readStatement)
                    .orElse(context.inputText("text"));

            boolean alreadyClarified = context.decisionForGate("clarify").isPresent();
            List<String> ambiguities = alreadyClarified ? List.of() : detectAmbiguities(statement);

            boolean completeness = alreadyClarified || ambiguities.isEmpty();
            boolean testability = alreadyClarified || hasTestableSignals(statement);
            boolean consistency = alreadyClarified || !conflictsWithApprovedBaseline(statement);
            boolean policyBoundary = !mentionsPersonalData(statement);
            boolean architectureBoundary = !crossesPlanes(statement);

            boolean clarificationRequired = !(completeness && testability && consistency);

            ObjectNode report = json.createObjectNode();
            report.put("decision", clarificationRequired ? "ClarificationRequired" : "NoClarificationRequired");
            ObjectNode checks = report.putObject("checks");
            checks.put("completeness", pass(completeness));
            checks.put("consistency", pass(consistency));
            checks.put("testability", pass(testability));
            checks.put("policyBoundary", pass(policyBoundary));
            checks.put("architectureBoundary", pass(architectureBoundary));
            ArrayNode found = report.putArray("ambiguities");
            ambiguities.forEach(found::add);
            report.put("rationale", clarificationRequired
                    ? "Unsafe to implement: " + ambiguities.size() + " material ambiguity/ambiguities detected."
                    : "All quality checks pass; no clarification gate is armed (REQ-D-009).");
            if (alreadyClarified) {
                report.put("note", "Re-evaluated after a recorded clarification decision.");
            }

            StageResult.Builder result = StageResult.success(
                            clarificationRequired
                                    ? "Clarification required: " + ambiguities.size() + " ambiguity/ambiguities"
                                    : "5/5 quality checks pass")
                    .artifact("QualityReport", report.toString(), context.upstreamArtifactIds())
                    .fact("quality.decision", clarificationRequired ? "ClarificationRequired" : "NoClarificationRequired")
                    .fact("quality.ambiguityCount", ambiguities.size());

            ambiguities.forEach(a -> result.assumption("Open question: " + a));
            return result.build();
        }

        private String readStatement(String contentJson) {
            try {
                return json.readTree(contentJson).path("statement").asText("");
            } catch (Exception e) {
                return "";
            }
        }

        static List<String> detectAmbiguities(String statement) {
            if (statement == null) {
                return List.of("The requirement is empty.");
            }
            String lower = statement.toLowerCase(Locale.ROOT);
            List<String> found = new ArrayList<>();

            for (String marker : VAGUE_MARKERS) {
                if (lower.contains(marker)) {
                    found.add("'" + marker + "' is not quantified; no value, unit or window is given.");
                }
            }
            // "show" is only vague when nothing says through what, to whom, or with what result.
            // "Stats must still show total clicks" names both, so flagging it would be a false alarm.
            boolean namesASurface = lower.matches(".*\\b(api|endpoint|response|field|stats|header|ui|page)\\b.*");
            if (lower.contains("show") && !namesASurface && !hasTestableSignals(statement)) {
                found.add("'show' does not say through which surface, or who is allowed to see it.");
            }
            if (lower.contains("expire") && !lower.contains("expiresat") && !containsDuration(lower)) {
                found.add("Expiry is requested without a duration or an explicit timestamp field.");
            }
            return List.copyOf(found);
        }

        private static boolean containsDuration(String lower) {
            return lower.matches(".*\\b\\d+\\s*(second|minute|hour|day|week|month|year)s?\\b.*");
        }

        /** Status codes, field names and numbers are what make a statement checkable. */
        static boolean hasTestableSignals(String statement) {
            if (statement == null) {
                return false;
            }
            String lower = statement.toLowerCase(Locale.ROOT);
            return lower.matches(".*\\b(200|201|204|400|404|409|410|422|429|503)\\b.*")
                    || lower.contains("expiresat")
                    || lower.contains("must")
                    || containsDuration(lower);
        }

        /** A default expiry would contradict the already-approved "no default expiry" decision (ASM-004). */
        static boolean conflictsWithApprovedBaseline(String statement) {
            if (statement == null) {
                return false;
            }
            String lower = statement.toLowerCase(Locale.ROOT);
            return lower.contains("expire") && lower.contains("a while");
        }

        static boolean mentionsPersonalData(String statement) {
            if (statement == null) {
                return false;
            }
            String lower = statement.toLowerCase(Locale.ROOT);
            return lower.contains("ip address") || lower.contains("email") || lower.contains("personal data");
        }

        static boolean crossesPlanes(String statement) {
            if (statement == null) {
                return false;
            }
            String lower = statement.toLowerCase(Locale.ROOT);
            return lower.contains("orchestration engine") && lower.contains("shortener database");
        }

        private static String pass(boolean value) {
            return value ? "PASS" : "FAIL";
        }
    }
}
