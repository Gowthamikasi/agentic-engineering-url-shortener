package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.Artifact;
import com.example.urlshortener.orchestration.model.Decision;
import com.example.urlshortener.orchestration.model.TransitionEvent;
import com.example.urlshortener.orchestration.port.ApprovalStore;
import com.example.urlshortener.orchestration.port.ArtifactStore;
import com.example.urlshortener.orchestration.port.InstanceStore;
import com.example.urlshortener.orchestration.port.Journal;
import com.example.urlshortener.telemetry.audit.AuditEvent;
import com.example.urlshortener.telemetry.audit.AuditHasher;
import com.example.urlshortener.telemetry.audit.AuditSink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory ports, so engine behaviour can be tested without a database. */
final class InMemoryStores {

    private InMemoryStores() {
    }

    static final class MemoryJournal implements Journal {

        private final List<TransitionEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void append(TransitionEvent event) {
            events.add(event);
        }

        @Override
        public List<TransitionEvent> findByRun(String runId) {
            return events.stream()
                    .filter(e -> e.runId().equals(runId))
                    .sorted(Comparator.comparingLong(TransitionEvent::seq))
                    .toList();
        }

        @Override
        public List<TransitionEvent> findAll() {
            return List.copyOf(events);
        }

        @Override
        public List<String> distinctRunIds() {
            return events.stream().map(TransitionEvent::runId).distinct().toList();
        }

        List<String> actionsFor(String runId, String nodeId) {
            return findByRun(runId).stream()
                    .filter(e -> nodeId.equals(e.nodeId()))
                    .map(TransitionEvent::action)
                    .toList();
        }

        List<TransitionEvent> forNode(String runId, String nodeId) {
            return findByRun(runId).stream().filter(e -> nodeId.equals(e.nodeId())).toList();
        }
    }

    static final class MemoryApprovalStore implements ApprovalStore {

        private final List<Decision> decisions = new CopyOnWriteArrayList<>();

        @Override
        public void save(String runId, Decision decision) {
            decisions.add(decision);
        }

        @Override
        public Optional<Decision> find(String runId, String gateId) {
            return decisions.stream().filter(d -> d.gateId().equals(gateId)).reduce((a, b) -> b);
        }

        @Override
        public List<Decision> findByRun(String runId) {
            return List.copyOf(decisions);
        }
    }

    static final class MemoryArtifactStore implements ArtifactStore {

        private final List<Artifact> artifacts = new CopyOnWriteArrayList<>();

        @Override
        public void save(String runId, Artifact artifact) {
            artifacts.add(artifact);
        }

        @Override
        public List<Artifact> findByRun(String runId) {
            return List.copyOf(artifacts);
        }
    }

    static final class MemoryInstanceStore implements InstanceStore {

        private final Map<String, InstanceRecord> records = new LinkedHashMap<>();

        @Override
        public synchronized void save(InstanceRecord record) {
            records.put(record.runId(), record);
        }

        @Override
        public synchronized Optional<InstanceRecord> find(String runId) {
            return Optional.ofNullable(records.get(runId));
        }

        @Override
        public synchronized List<InstanceRecord> findAll() {
            return new ArrayList<>(records.values());
        }
    }

    /** Keeps the real hash chain, so tests can check the trail verifies rather than just exists. */
    static final class MemoryAuditSink implements AuditSink {

        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public synchronized AuditEvent append(AuditEvent unsealed) {
            String prevHash = events.stream()
                    .filter(e -> e.runId().equals(unsealed.runId()))
                    .reduce((a, b) -> b)
                    .map(AuditEvent::hash)
                    .orElse(AuditEvent.GENESIS_HASH);

            AuditEvent sealed = AuditHasher.seal(new AuditEvent(unsealed.runId(), unsealed.seq(),
                    unsealed.timestamp(), unsealed.actorType(), unsealed.actorId(), unsealed.action(),
                    unsealed.targetType(), unsealed.targetId(), unsealed.result(), unsealed.reason(),
                    unsealed.policyVersion(), unsealed.definitionVersion(), prevHash, null));
            events.add(sealed);
            return sealed;
        }

        @Override
        public List<AuditEvent> findByRun(String runId) {
            return events.stream()
                    .filter(e -> e.runId().equals(runId))
                    .sorted(Comparator.comparingLong(AuditEvent::seq))
                    .toList();
        }

        @Override
        public List<AuditEvent> findAll() {
            return List.copyOf(events);
        }
    }
}
