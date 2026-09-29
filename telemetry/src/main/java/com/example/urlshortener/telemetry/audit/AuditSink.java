package com.example.urlshortener.telemetry.audit;

import java.util.List;

/** Append-only audit store. */
public interface AuditSink {

    /** Seals the event into the chain for its run and persists it. */
    AuditEvent append(AuditEvent unsealedWithoutHashes);

    List<AuditEvent> findByRun(String runId);

    List<AuditEvent> findAll();
}
