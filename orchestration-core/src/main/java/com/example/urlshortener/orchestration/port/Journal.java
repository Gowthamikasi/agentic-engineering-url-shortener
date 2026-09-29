package com.example.urlshortener.orchestration.port;

import com.example.urlshortener.orchestration.model.TransitionEvent;

import java.util.List;

/**
 * Append-only transition journal — the source of truth for a run.
 *
 * <p>There is no update and no delete. The snapshot the API serves is derived from these rows, so
 * a run can be reconstructed after a restart by replaying them.
 */
public interface Journal {

    void append(TransitionEvent event);

    List<TransitionEvent> findByRun(String runId);

    List<TransitionEvent> findAll();

    List<String> distinctRunIds();
}
