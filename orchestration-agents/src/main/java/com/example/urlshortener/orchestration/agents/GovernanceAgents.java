package com.example.urlshortener.orchestration.agents;

import com.example.urlshortener.orchestration.engine.StageAgent;
import com.example.urlshortener.orchestration.engine.StageContext;
import com.example.urlshortener.orchestration.engine.StageResult;
import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Decision;
import com.example.urlshortener.orchestration.model.FailureClass;
import com.example.urlshortener.policy.PolicyContext;
import com.example.urlshortener.policy.PolicyEvaluation;
import com.example.urlshortener.policy.PolicyEvaluator;
import com.example.urlshortener.policy.PolicyException;
import com.example.urlshortener.policy.PolicySet;
import com.example.urlshortener.policy.PolicySetLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/** Policy evaluation and the run summary. */
public final class GovernanceAgents {

    private GovernanceAgents() {
    }

    /** Evaluates the versioned policy set over everything the run produced. */
    public static class PolicyEvaluationAgent implements StageAgent {

        private final ObjectMapper json;
        private final PolicyEvaluator evaluator;
        private final PolicySetLoader loader;
        private final Supplier<List<PolicyException>> exceptions;
        private final Clock clock;

        public PolicyEvaluationAgent(ObjectMapper json, PolicyEvaluator evaluator, PolicySetLoader loader,
                                     Supplier<List<PolicyException>> exceptions, Clock clock) {
            this.json = json;
            this.evaluator = evaluator;
            this.loader = loader;
            this.exceptions = exceptions;
            this.clock = clock;
        }

        @Override
        public String agentType() {
            return "PolicyEvaluationAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            PolicySet policySet = loader.loadVersion(context.policyVersion());

            PolicyContext policyContext = PolicyContext.builder()
                    .facts(context.facts())
                    .exceptions(exceptions.get())
                    .build();

            PolicyEvaluator.Result result = evaluator.evaluate(policySet, policyContext, clock.instant());

            ObjectNode report = json.createObjectNode();
            report.put("policyVersion", result.policyVersion());
            report.put("summary", result.summary());
            report.put("releaseBlocked", result.releaseBlocked());
            ArrayNode blocking = report.putArray("blockingPolicyIds");
            result.blockingPolicyIds().forEach(blocking::add);

            ArrayNode evaluations = report.putArray("evaluations");
            for (PolicyEvaluation evaluation : result.evaluations()) {
                ObjectNode node = evaluations.addObject();
                node.put("policyId", evaluation.policyId());
                node.put("domain", evaluation.domain().name());
                node.put("mandatory", evaluation.mandatory());
                node.put("outcome", evaluation.outcome().name());
                node.put("reason", evaluation.reason());
                node.put("evidence", evaluation.evidence());
            }

            StageResult.Builder builder = result.releaseBlocked()
                    ? StageResult.failure(FailureClass.PERMANENT,
                    "Release blocked by mandatory policy failure(s): " + result.blockingPolicyIds())
                    : StageResult.success(result.summary());

            return builder
                    .artifact("PolicyEvaluation", report.toString(), context.upstreamArtifactIds())
                    .fact("policy.summary", result.summary())
                    .fact("policy.releaseBlocked", result.releaseBlocked())
                    .fact("policy.version", result.policyVersion())
                    .build();
        }
    }

    /** Assembles the run summary from what the run actually recorded. */
    public static class SummaryAgent implements StageAgent {

        private final ObjectMapper json;

        public SummaryAgent(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String agentType() {
            return "SummaryAgent";
        }

        @Override
        public StageResult execute(StageContext context) {
            ObjectNode summary = json.createObjectNode();
            summary.put("runId", context.runId());
            summary.put("requirementId", context.inputText("requirementId"));
            summary.put("kind", context.inputText("kind"));
            summary.put("definitionVersion", context.definitionVersion());
            summary.put("policyVersion", context.policyVersion());

            ArrayNode decisions = summary.putArray("decisions");
            for (Decision decision : context.decisions()) {
                ObjectNode node = decisions.addObject();
                node.put("gateId", decision.gateId());
                node.put("decision", decision.decision());
                node.put("actor", decision.actor());
                node.put("rationale", decision.rationale());
                node.put("at", decision.timestamp().toString());
            }

            ArrayNode artifacts = summary.putArray("upstreamArtifacts");
            context.upstreamArtifacts().stream().map(Artifact::artifactId).forEach(artifacts::add);

            ArrayNode assumptions = summary.putArray("openAssumptions");
            context.assumptions().forEach(assumptions::add);

            summary.put("policySummary", String.valueOf(context.facts().get("policy.summary")));
            summary.put("degraded", Boolean.TRUE.equals(context.facts().get("run.degraded")));

            return StageResult.success("Run summary assembled with " + decisions.size() + " recorded decision(s)")
                    .artifact("RunSummary", summary.toString(), context.upstreamArtifactIds())
                    .build();
        }
    }
}
