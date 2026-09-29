package com.example.urlshortener.orchestration.port;

import com.example.urlshortener.orchestration.model.Decision;

import java.util.List;
import java.util.Optional;

/** Persisted human decisions. A gate's state change is only valid if a row exists here first. */
public interface ApprovalStore {

    void save(String runId, Decision decision);

    Optional<Decision> find(String runId, String gateId);

    List<Decision> findByRun(String runId);
}
