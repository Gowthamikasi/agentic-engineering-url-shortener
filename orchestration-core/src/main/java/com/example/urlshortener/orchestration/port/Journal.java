package com.example.urlshortener.orchestration.port;

import com.example.urlshortener.orchestration.model.TransitionEvent;

import java.util.List;

/** The append-only transition journal, and the source of truth for a run. */
public interface Journal {

    void append(TransitionEvent event);

    List<TransitionEvent> findByRun(String runId);

    List<TransitionEvent> findAll();

    List<String> distinctRunIds();
}
