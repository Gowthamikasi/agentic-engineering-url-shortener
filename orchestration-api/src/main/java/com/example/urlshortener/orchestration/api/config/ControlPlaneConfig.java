package com.example.urlshortener.orchestration.api.config;

import com.example.urlshortener.orchestration.agents.DesignAgents;
import com.example.urlshortener.orchestration.agents.GovernanceAgents;
import com.example.urlshortener.orchestration.agents.RequirementAgents;
import com.example.urlshortener.orchestration.agents.SurefireReportReader;
import com.example.urlshortener.orchestration.agents.VerificationAgents;
import com.example.urlshortener.orchestration.engine.EngineSettings;
import com.example.urlshortener.orchestration.engine.StageAgent;
import com.example.urlshortener.orchestration.engine.WorkflowDefinitionLoader;
import com.example.urlshortener.orchestration.engine.WorkflowEngine;
import com.example.urlshortener.orchestration.infrastructure.PolicyExceptionStore;
import com.example.urlshortener.orchestration.model.WorkflowDefinition;
import com.example.urlshortener.orchestration.port.ApprovalStore;
import com.example.urlshortener.orchestration.port.InstanceStore;
import com.example.urlshortener.orchestration.port.Journal;
import com.example.urlshortener.policy.DefaultPolicyChecks;
import com.example.urlshortener.policy.PolicyEvaluator;
import com.example.urlshortener.policy.PolicySetLoader;
import com.example.urlshortener.telemetry.audit.AuditSink;
import com.example.urlshortener.telemetry.metrics.ReliabilityMetricsCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * Wires the control plane.
 *
 * <p>Agents are declared here, as a list, so the set of things the engine is willing to execute is
 * visible in one place. An agent that is not in this list cannot be reached from a definition
 * file, which keeps a workflow definition from being a way to run arbitrary code.
 */
@Configuration
@EnableConfigurationProperties(OrchestrationProperties.class)
public class ControlPlaneConfig {

    @Bean
    public WorkflowDefinitionLoader workflowDefinitionLoader(ObjectMapper objectMapper) {
        return new WorkflowDefinitionLoader(objectMapper);
    }

    @Bean
    public WorkflowDefinition sdlcWorkflowDefinition(WorkflowDefinitionLoader loader,
                                                     OrchestrationProperties properties) {
        return loader.fromClasspath(properties.getDefinitionResource());
    }

    @Bean
    public PolicySetLoader policySetLoader(ObjectMapper objectMapper) {
        return new PolicySetLoader(objectMapper);
    }

    @Bean
    public PolicyEvaluator policyEvaluator() {
        return new PolicyEvaluator(DefaultPolicyChecks.registry());
    }

    @Bean
    public ReliabilityMetricsCalculator reliabilityMetricsCalculator() {
        return new ReliabilityMetricsCalculator();
    }

    @Bean
    public SurefireReportReader surefireReportReader(OrchestrationProperties properties) {
        return new SurefireReportReader(Path.of(properties.getProjectRoot()));
    }

    @Bean
    public List<StageAgent> stageAgents(ObjectMapper objectMapper,
                                        SurefireReportReader reports,
                                        PolicyEvaluator policyEvaluator,
                                        PolicySetLoader policySetLoader,
                                        PolicyExceptionStore exceptions,
                                        Clock clock) {
        return List.of(
                new RequirementAgents.RequirementIngestAgent(objectMapper),
                new RequirementAgents.RequirementNormalizeAgent(objectMapper),
                new RequirementAgents.RequirementQualityAgent(objectMapper),
                new DesignAgents.HumanGateAgent(),
                new DesignAgents.ImpactAnalysisAgent(objectMapper),
                new DesignAgents.DecomposeAgent(objectMapper),
                new DesignAgents.ContractAgent(objectMapper),
                new DesignAgents.TestPlanAgent(objectMapper),
                new DesignAgents.ImplementAgent(objectMapper),
                new VerificationAgents.TestRunnerAgent(objectMapper, reports),
                new VerificationAgents.SecurityScanAgent(objectMapper),
                new VerificationAgents.DocsAgent(objectMapper),
                new GovernanceAgents.PolicyEvaluationAgent(objectMapper, policyEvaluator, policySetLoader,
                        exceptions::findAll, clock),
                new GovernanceAgents.SummaryAgent(objectMapper));
    }

    @Bean(destroyMethod = "close")
    public WorkflowEngine workflowEngine(List<StageAgent> agents, Journal journal, ApprovalStore approvals,
                                         InstanceStore instances, AuditSink audit, Clock clock,
                                         OrchestrationProperties properties) {
        EngineSettings settings = new EngineSettings(properties.getMaxParallelism(),
                properties.getApprovalTimeout(), 0.2);
        return new WorkflowEngine(agents, journal, approvals, instances, audit, clock, settings);
    }
}
