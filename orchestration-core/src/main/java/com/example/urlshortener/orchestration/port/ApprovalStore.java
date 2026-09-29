package com.example.urlshortener.orchestration.port;

import com.example.urlshortener.orchestration.model.Decision;

import java.util.List;
import java.util.Optional;

/** Persisted human decisions. A gate only changes state once a row exists here. */
public interface ApprovalStore {

    void save(String runId, Decision decision);

    Optional<Decision> find(String runId, String gateId);

    List<Decision> findByRun(String runId);
}
