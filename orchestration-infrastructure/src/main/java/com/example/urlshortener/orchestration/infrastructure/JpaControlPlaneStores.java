package com.example.urlshortener.orchestration.infrastructure;

import com.example.urlshortener.orchestration.infrastructure.jpa.ApprovalJpaRepository;
import com.example.urlshortener.orchestration.infrastructure.jpa.ArtifactJpaRepository;
import com.example.urlshortener.orchestration.infrastructure.jpa.AuditJpaRepository;
import com.example.urlshortener.orchestration.infrastructure.jpa.InstanceJpaRepository;
import com.example.urlshortener.orchestration.infrastructure.jpa.JournalJpaRepository;
import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Decision;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.TransitionEvent;
import com.example.urlshortener.orchestration.port.ApprovalStore;
import com.example.urlshortener.orchestration.port.ArtifactStore;
import com.example.urlshortener.orchestration.port.InstanceStore;
import com.example.urlshortener.orchestration.port.Journal;
import com.example.urlshortener.telemetry.audit.ActorType;
import com.example.urlshortener.telemetry.audit.AuditEvent;
import com.example.urlshortener.telemetry.audit.AuditHasher;
import com.example.urlshortener.telemetry.audit.AuditSink;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** JPA implementations of the control-plane ports. */
public final class JpaControlPlaneStores {

    private JpaControlPlaneStores() {
    }

    /** The transition journal. */
    @Repository
    public static class JpaJournal implements Journal {

        private final JournalJpaRepository jpa;

        public JpaJournal(JournalJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void append(TransitionEvent event) {
            jpa.save(new ControlPlaneEntities.JournalEntity(
                    event.runId(), event.seq(), event.nodeId(),
                    event.fromState() == null ? null : event.fromState().name(),
                    event.toState() == null ? null : event.toState().name(),
                    event.action(), event.actorType(), event.actorId(), event.result(), event.reason(),
                    event.definitionVersion(), event.policyVersion(), event.timestamp(), event.payloadJson()));
        }

        @Override
        @Transactional(readOnly = true)
        public List<TransitionEvent> findByRun(String runId) {
            return jpa.findByRunIdOrderBySeqAsc(runId).stream().map(JpaJournal::toDomain).toList();
        }

        @Override
        @Transactional(readOnly = true)
        public List<TransitionEvent> findAll() {
            return jpa.findAllByOrderByRunIdAscSeqAsc().stream().map(JpaJournal::toDomain).toList();
        }

        @Override
        @Transactional(readOnly = true)
        public List<String> distinctRunIds() {
            return jpa.findDistinctRunIds();
        }

        private static TransitionEvent toDomain(ControlPlaneEntities.JournalEntity e) {
            return new TransitionEvent(e.getRunId(), e.getSeq(), e.getNodeId(),
                    e.getFromState() == null ? null : NodeState.valueOf(e.getFromState()),
                    e.getToState() == null ? null : NodeState.valueOf(e.getToState()),
                    e.getAction(), e.getActorType(), e.getActorId(), e.getResult(), e.getReason(),
                    e.getDefinitionVersion(), e.getPolicyVersion(), e.getOccurredAt(), e.getPayloadJson());
        }
    }

    /** Human decisions. */
    @Repository
    public static class JpaApprovalStore implements ApprovalStore {

        private final ApprovalJpaRepository jpa;

        public JpaApprovalStore(ApprovalJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void save(String runId, Decision decision) {
            jpa.save(new ControlPlaneEntities.ApprovalEntity(decision.decisionId(), runId, decision.gateId(),
                    decision.type(), decision.actor(), decision.decision(), decision.rationale(),
                    decision.conditions(), String.join(",", decision.affectedArtifacts()), decision.timestamp()));
        }

        @Override
        @Transactional(readOnly = true)
        public Optional<Decision> find(String runId, String gateId) {
            List<ControlPlaneEntities.ApprovalEntity> found =
                    jpa.findByRunIdAndGateIdOrderByDecidedAtAsc(runId, gateId);
            return found.isEmpty() ? Optional.empty() : Optional.of(toDomain(found.get(found.size() - 1)));
        }

        @Override
        @Transactional(readOnly = true)
        public List<Decision> findByRun(String runId) {
            return jpa.findByRunIdOrderByDecidedAtAsc(runId).stream().map(JpaApprovalStore::toDomain).toList();
        }

        private static Decision toDomain(ControlPlaneEntities.ApprovalEntity e) {
            List<String> artifacts = e.getAffectedArtifacts() == null || e.getAffectedArtifacts().isBlank()
                    ? List.of() : Arrays.asList(e.getAffectedArtifacts().split(","));
            return new Decision(e.getDecisionId(), e.getGateId(), e.getDecisionType(), e.getActor(),
                    e.getDecision(), e.getRationale(), e.getConditions(), artifacts, e.getDecidedAt());
        }
    }

    /** Node outputs, so lineage and artifacts survive a restart. */
    @Repository
    public static class JpaArtifactStore implements ArtifactStore {

        private final ArtifactJpaRepository jpa;

        public JpaArtifactStore(ArtifactJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void save(String runId, Artifact artifact) {
            jpa.save(new ControlPlaneEntities.ArtifactEntity(artifact.artifactId(), runId, artifact.nodeId(),
                    artifact.type(), artifact.version(), artifact.sha256(), artifact.contentJson(),
                    String.join(",", artifact.inputArtifactIds()), String.join(",", artifact.decisionIds()),
                    artifact.producedAt(), artifact.degraded()));
        }

        @Override
        @Transactional(readOnly = true)
        public List<Artifact> findByRun(String runId) {
            return jpa.findByRunIdOrderByProducedAtAsc(runId).stream().map(JpaArtifactStore::toDomain).toList();
        }

        private static Artifact toDomain(ControlPlaneEntities.ArtifactEntity e) {
            return new Artifact(e.getArtifactId(), e.getNodeId(), e.getArtifactType(), e.getVersion(),
                    e.getSha256(), e.getContentJson(), split(e.getInputArtifactIds()),
                    split(e.getDecisionIds()), e.getProducedAt(), e.isDegraded());
        }

        private static List<String> split(String csv) {
            return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
        }
    }

    /** Run headers, so a restarted process can list runs without reading the journal. */
    @Repository
    public static class JpaInstanceStore implements InstanceStore {

        private final InstanceJpaRepository jpa;

        public JpaInstanceStore(InstanceJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void save(InstanceRecord record) {
            jpa.save(new ControlPlaneEntities.InstanceEntity(record.runId(), record.definitionName(),
                    record.definitionVersion(), record.policyVersion(), record.state(), record.terminalOutcome(),
                    record.createdAt(), record.terminalAt(), record.inputJson(), record.factsJson()));
        }

        @Override
        @Transactional(readOnly = true)
        public Optional<InstanceRecord> find(String runId) {
            return jpa.findById(runId).map(JpaInstanceStore::toDomain);
        }

        @Override
        @Transactional(readOnly = true)
        public List<InstanceRecord> findAll() {
            return jpa.findAll().stream().map(JpaInstanceStore::toDomain).toList();
        }

        private static InstanceRecord toDomain(ControlPlaneEntities.InstanceEntity e) {
            return new InstanceRecord(e.getRunId(), e.getDefinitionName(), e.getDefinitionVersion(),
                    e.getPolicyVersion(), e.getState(), e.getTerminalOutcome(), e.getCreatedAt(),
                    e.getTerminalAt(), e.getInputJson(), e.getFactsJson());
        }
    }

    /** The audit trail. */
    @Repository
    public static class JpaAuditSink implements AuditSink {

        private final AuditJpaRepository jpa;

        public JpaAuditSink(AuditJpaRepository jpa) {
            this.jpa = jpa;
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public synchronized AuditEvent append(AuditEvent unsealed) {
            List<ControlPlaneEntities.AuditEntity> existing = jpa.findByRunIdOrderBySeqAsc(unsealed.runId());
            String prevHash = existing.isEmpty()
                    ? AuditEvent.GENESIS_HASH
                    : existing.get(existing.size() - 1).getHash();

            AuditEvent withPrev = new AuditEvent(unsealed.runId(), unsealed.seq(), unsealed.timestamp(),
                    unsealed.actorType(), unsealed.actorId(), unsealed.action(), unsealed.targetType(),
                    unsealed.targetId(), unsealed.result(), unsealed.reason(), unsealed.policyVersion(),
                    unsealed.definitionVersion(), prevHash, null);
            AuditEvent sealed = AuditHasher.seal(withPrev);

            jpa.save(new ControlPlaneEntities.AuditEntity(sealed.runId(), sealed.seq(), sealed.timestamp(),
                    sealed.actorType().name(), sealed.actorId(), sealed.action(), sealed.targetType(),
                    sealed.targetId(), sealed.result(), sealed.reason(), sealed.policyVersion(),
                    sealed.definitionVersion(), sealed.prevHash(), sealed.hash()));
            return sealed;
        }

        @Override
        @Transactional(readOnly = true)
        public List<AuditEvent> findByRun(String runId) {
            return jpa.findByRunIdOrderBySeqAsc(runId).stream().map(JpaAuditSink::toDomain).toList();
        }

        @Override
        @Transactional(readOnly = true)
        public List<AuditEvent> findAll() {
            return jpa.findAllByOrderByRunIdAscSeqAsc().stream().map(JpaAuditSink::toDomain).toList();
        }

        private static AuditEvent toDomain(ControlPlaneEntities.AuditEntity e) {
            return new AuditEvent(e.getRunId(), e.getSeq(), e.getOccurredAt(),
                    ActorType.valueOf(e.getActorType()), e.getActorId(), e.getAction(), e.getTargetType(),
                    e.getTargetId(), e.getResult(), e.getReason(), e.getPolicyVersion(),
                    e.getDefinitionVersion(), e.getPrevHash(), e.getHash());
        }
    }
}
