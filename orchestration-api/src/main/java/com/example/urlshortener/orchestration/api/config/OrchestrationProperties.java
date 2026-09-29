package com.example.urlshortener.orchestration.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Externalised configuration for the control plane. */
@ConfigurationProperties(prefix = "orchestration")
public class OrchestrationProperties {

    /** Classpath location of the workflow definition to run. */
    private String definitionResource = "/workflows/sdlc.v1.json";

    /** Policy set version every run is stamped with (REQ-D-003). */
    private String policyVersion = "1.0.0";

    /** How many node attempts may run concurrently. */
    private int maxParallelism = 4;

    /** How long a gate waits before safe-stopping. Never before approving (REQ-D-010). */
    private Duration approvalTimeout = Duration.ofHours(24);

    /** Where the test-runner agent looks for Surefire and Failsafe reports. */
    private String projectRoot = ".";

    /** Where scenario evidence bundles are written. */
    private String evidenceDir = "docs/scenarios";

    public String getDefinitionResource() {
        return definitionResource;
    }

    public void setDefinitionResource(String definitionResource) {
        this.definitionResource = definitionResource;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public int getMaxParallelism() {
        return maxParallelism;
    }

    public void setMaxParallelism(int maxParallelism) {
        this.maxParallelism = maxParallelism;
    }

    public Duration getApprovalTimeout() {
        return approvalTimeout;
    }

    public void setApprovalTimeout(Duration approvalTimeout) {
        this.approvalTimeout = approvalTimeout;
    }

    public String getProjectRoot() {
        return projectRoot;
    }

    public void setProjectRoot(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    public String getEvidenceDir() {
        return evidenceDir;
    }

    public void setEvidenceDir(String evidenceDir) {
        this.evidenceDir = evidenceDir;
    }
}
