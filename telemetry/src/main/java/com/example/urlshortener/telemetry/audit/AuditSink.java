package com.example.urlshortener.telemetry.audit;

import java.util.List;

/**
 * Append-only audit store. There is deliberately no update or delete operation on this port,
 * so "append-only" is a property of the type and not just of the SQL grants.
 */
public interface AuditSink {

    /** Seals the event into the chain for its run and persists it. */
    AuditEvent append(AuditEvent unsealedWithoutHashes);

    List<AuditEvent> findByRun(String runId);

    List<AuditEvent> findAll();
}
