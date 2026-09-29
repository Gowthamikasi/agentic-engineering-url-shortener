package com.example.urlshortener.orchestration.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mappings for the control plane. Kept together because they are one schema, not one per file. */
public final class ControlPlaneEntities {

    private ControlPlaneEntities() {
    }

    // The entity name is stated explicitly: for a nested class Hibernate would otherwise derive
    // a name containing the enclosing class, which JPQL queries cannot refer to.
    @Entity(name = "InstanceEntity")
    @Table(name = "workflow_instances")
    public static class InstanceEntity {

        @Id
        @Column(name = "run_id", length = 64)
        private String runId;

        @Column(name = "definition_name", length = 64, nullable = false)
        private String definitionName;

        @Column(name = "definition_version", nullable = false)
        private long definitionVersion;

        @Column(name = "policy_version", length = 32, nullable = false)
        private String policyVersion;

        @Column(name = "state", length = 32, nullable = false)
        private String state;

        @Column(name = "terminal_outcome", length = 512)
        private String terminalOutcome;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        @Column(name = "terminal_at")
        private Instant terminalAt;

        @Lob
        @Column(name = "input_json")
        private String inputJson;

        protected InstanceEntity() {
        }

        public InstanceEntity(String runId, String definitionName, long definitionVersion, String policyVersion,
                              String state, String terminalOutcome, Instant createdAt, Instant terminalAt,
                              String inputJson) {
            this.runId = runId;
            this.definitionName = definitionName;
            this.definitionVersion = definitionVersion;
            this.policyVersion = policyVersion;
            this.state = state;
            this.terminalOutcome = terminalOutcome;
            this.createdAt = createdAt;
            this.terminalAt = terminalAt;
            this.inputJson = inputJson;
        }

        public String getRunId() {
            return runId;
        }

        public String getDefinitionName() {
            return definitionName;
        }

        public long getDefinitionVersion() {
            return definitionVersion;
        }

        public String getPolicyVersion() {
            return policyVersion;
        }

        public String getState() {
            return state;
        }

        public String getTerminalOutcome() {
            return terminalOutcome;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getTerminalAt() {
            return terminalAt;
        }

        public String getInputJson() {
            return inputJson;
        }
    }

    // The entity name is stated explicitly: for a nested class Hibernate would otherwise derive
    // a name containing the enclosing class, which JPQL queries cannot refer to.
    @Entity(name = "JournalEntity")
    @Table(name = "workflow_journal")
    public static class JournalEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "run_id", length = 64, nullable = false)
        private String runId;

        @Column(name = "seq", nullable = false)
        private long seq;

        @Column(name = "node_id", length = 64)
        private String nodeId;

        @Column(name = "from_state", length = 32)
        private String fromState;

        @Column(name = "to_state", length = 32)
        private String toState;

        @Column(name = "action", length = 64, nullable = false)
        private String action;

        @Column(name = "actor_type", length = 16, nullable = false)
        private String actorType;

        @Column(name = "actor_id", length = 128)
        private String actorId;

        @Column(name = "result", length = 512)
        private String result;

        @Column(name = "reason", length = 2000)
        private String reason;

        @Column(name = "definition_version", nullable = false)
        private long definitionVersion;

        @Column(name = "policy_version", length = 32)
        private String policyVersion;

        @Column(name = "occurred_at", nullable = false)
        private Instant occurredAt;

        @Lob
        @Column(name = "payload_json")
        private String payloadJson;

        protected JournalEntity() {
        }

        public JournalEntity(String runId, long seq, String nodeId, String fromState, String toState, String action,
                             String actorType, String actorId, String result, String reason, long definitionVersion,
                             String policyVersion, Instant occurredAt, String payloadJson) {
            this.runId = runId;
            this.seq = seq;
            this.nodeId = nodeId;
            this.fromState = fromState;
            this.toState = toState;
            this.action = action;
            this.actorType = actorType;
            this.actorId = actorId;
            this.result = result;
            this.reason = truncate(reason, 2000);
            this.definitionVersion = definitionVersion;
            this.policyVersion = policyVersion;
            this.occurredAt = occurredAt;
            this.payloadJson = payloadJson;
        }

        public String getRunId() {
            return runId;
        }

        public long getSeq() {
            return seq;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getFromState() {
            return fromState;
        }

        public String getToState() {
            return toState;
        }

        public String getAction() {
            return action;
        }

        public String getActorType() {
            return actorType;
        }

        public String getActorId() {
            return actorId;
        }

        public String getResult() {
            return result;
        }

        public String getReason() {
            return reason;
        }

        public long getDefinitionVersion() {
            return definitionVersion;
        }

        public String getPolicyVersion() {
            return policyVersion;
        }

        public Instant getOccurredAt() {
            return occurredAt;
        }

        public String getPayloadJson() {
            return payloadJson;
        }
    }

    // The entity name is stated explicitly: for a nested class Hibernate would otherwise derive
    // a name containing the enclosing class, which JPQL queries cannot refer to.
    @Entity(name = "ApprovalEntity")
    @Table(name = "approval_decisions")
    public static class ApprovalEntity {

        @Id
        @Column(name = "decision_id", length = 128)
        private String decisionId;

        @Column(name = "run_id", length = 64, nullable = false)
        private String runId;

        @Column(name = "gate_id", length = 64, nullable = false)
        private String gateId;

        @Column(name = "decision_type", length = 32, nullable = false)
        private String decisionType;

        @Column(name = "actor", length = 128, nullable = false)
        private String actor;

        @Column(name = "decision", length = 64, nullable = false)
        private String decision;

        @Column(name = "rationale", length = 2000, nullable = false)
        private String rationale;

        @Column(name = "conditions", length = 2000)
        private String conditions;

        @Column(name = "affected_artifacts", length = 2000)
        private String affectedArtifacts;

        @Column(name = "decided_at", nullable = false)
        private Instant decidedAt;

        protected ApprovalEntity() {
        }

        public ApprovalEntity(String decisionId, String runId, String gateId, String decisionType, String actor,
                              String decision, String rationale, String conditions, String affectedArtifacts,
                              Instant decidedAt) {
            this.decisionId = decisionId;
            this.runId = runId;
            this.gateId = gateId;
            this.decisionType = decisionType;
            this.actor = actor;
            this.decision = decision;
            this.rationale = truncate(rationale, 2000);
            this.conditions = truncate(conditions, 2000);
            this.affectedArtifacts = truncate(affectedArtifacts, 2000);
            this.decidedAt = decidedAt;
        }

        public String getDecisionId() {
            return decisionId;
        }

        public String getRunId() {
            return runId;
        }

        public String getGateId() {
            return gateId;
        }

        public String getDecisionType() {
            return decisionType;
        }

        public String getActor() {
            return actor;
        }

        public String getDecision() {
            return decision;
        }

        public String getRationale() {
            return rationale;
        }

        public String getConditions() {
            return conditions;
        }

        public String getAffectedArtifacts() {
            return affectedArtifacts;
        }

        public Instant getDecidedAt() {
            return decidedAt;
        }
    }

    // The entity name is stated explicitly: for a nested class Hibernate would otherwise derive
    // a name containing the enclosing class, which JPQL queries cannot refer to.
    @Entity(name = "AuditEntity")
    @Table(name = "audit_events")
    public static class AuditEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "run_id", length = 64, nullable = false)
        private String runId;

        @Column(name = "seq", nullable = false)
        private long seq;

        @Column(name = "occurred_at", nullable = false)
        private Instant occurredAt;

        @Column(name = "actor_type", length = 16, nullable = false)
        private String actorType;

        @Column(name = "actor_id", length = 128)
        private String actorId;

        @Column(name = "action", length = 64, nullable = false)
        private String action;

        @Column(name = "target_type", length = 32)
        private String targetType;

        @Column(name = "target_id", length = 128)
        private String targetId;

        @Column(name = "result", length = 512)
        private String result;

        @Column(name = "reason", length = 2000)
        private String reason;

        @Column(name = "policy_version", length = 32)
        private String policyVersion;

        @Column(name = "definition_version", nullable = false)
        private long definitionVersion;

        @Column(name = "prev_hash", length = 64, nullable = false)
        private String prevHash;

        @Column(name = "hash", length = 64, nullable = false)
        private String hash;

        protected AuditEntity() {
        }

        public AuditEntity(String runId, long seq, Instant occurredAt, String actorType, String actorId,
                           String action, String targetType, String targetId, String result, String reason,
                           String policyVersion, long definitionVersion, String prevHash, String hash) {
            this.runId = runId;
            this.seq = seq;
            this.occurredAt = occurredAt;
            this.actorType = actorType;
            this.actorId = actorId;
            this.action = action;
            this.targetType = targetType;
            this.targetId = targetId;
            this.result = truncate(result, 512);
            this.reason = truncate(reason, 2000);
            this.policyVersion = policyVersion;
            this.definitionVersion = definitionVersion;
            this.prevHash = prevHash;
            this.hash = hash;
        }

        public String getRunId() {
            return runId;
        }

        public long getSeq() {
            return seq;
        }

        public Instant getOccurredAt() {
            return occurredAt;
        }

        public String getActorType() {
            return actorType;
        }

        public String getActorId() {
            return actorId;
        }

        public String getAction() {
            return action;
        }

        public String getTargetType() {
            return targetType;
        }

        public String getTargetId() {
            return targetId;
        }

        public String getResult() {
            return result;
        }

        public String getReason() {
            return reason;
        }

        public String getPolicyVersion() {
            return policyVersion;
        }

        public long getDefinitionVersion() {
            return definitionVersion;
        }

        public String getPrevHash() {
            return prevHash;
        }

        public String getHash() {
            return hash;
        }
    }

    // The entity name is stated explicitly: for a nested class Hibernate would otherwise derive
    // a name containing the enclosing class, which JPQL queries cannot refer to.
    @Entity(name = "PolicyExceptionEntity")
    @Table(name = "policy_exceptions")
    public static class PolicyExceptionEntity {

        @Id
        @Column(name = "exception_id", length = 128)
        private String exceptionId;

        @Column(name = "run_id", length = 64)
        private String runId;

        @Column(name = "policy_id", length = 64, nullable = false)
        private String policyId;

        @Column(name = "reason", length = 2000, nullable = false)
        private String reason;

        @Column(name = "scope", length = 512, nullable = false)
        private String scope;

        @Column(name = "approver", length = 128)
        private String approver;

        @Column(name = "compensating_control", length = 2000)
        private String compensatingControl;

        @Column(name = "approved_at")
        private Instant approvedAt;

        @Column(name = "expires_at")
        private Instant expiresAt;

        @Column(name = "review_condition", length = 512)
        private String reviewCondition;

        protected PolicyExceptionEntity() {
        }

        public PolicyExceptionEntity(String exceptionId, String runId, String policyId, String reason, String scope,
                                     String approver, String compensatingControl, Instant approvedAt,
                                     Instant expiresAt, String reviewCondition) {
            this.exceptionId = exceptionId;
            this.runId = runId;
            this.policyId = policyId;
            this.reason = truncate(reason, 2000);
            this.scope = truncate(scope, 512);
            this.approver = approver;
            this.compensatingControl = truncate(compensatingControl, 2000);
            this.approvedAt = approvedAt;
            this.expiresAt = expiresAt;
            this.reviewCondition = truncate(reviewCondition, 512);
        }

        public String getExceptionId() {
            return exceptionId;
        }

        public String getRunId() {
            return runId;
        }

        public String getPolicyId() {
            return policyId;
        }

        public String getReason() {
            return reason;
        }

        public String getScope() {
            return scope;
        }

        public String getApprover() {
            return approver;
        }

        public String getCompensatingControl() {
            return compensatingControl;
        }

        public Instant getApprovedAt() {
            return approvedAt;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public String getReviewCondition() {
            return reviewCondition;
        }
    }

    /** Long reasons are trimmed rather than allowed to fail the insert that records why something happened. */
    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }
}
