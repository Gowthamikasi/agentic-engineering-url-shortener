package com.example.urlshortener.orchestration.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable header per run, so a restarted process can list runs without reading the journal. */
public interface InstanceStore {

    /** Header row; the full state lives in the journal. */
    record InstanceRecord(String runId, String definitionName, long definitionVersion, String policyVersion,
                          String state, String terminalOutcome, Instant createdAt, Instant terminalAt,
                          String inputJson) {
    }

    void save(InstanceRecord record);

    Optional<InstanceRecord> find(String runId);

    List<InstanceRecord> findAll();
}
