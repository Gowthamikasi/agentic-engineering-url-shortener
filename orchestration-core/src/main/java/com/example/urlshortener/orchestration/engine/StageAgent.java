package com.example.urlshortener.orchestration.engine;

/** A stage executor. */
public interface StageAgent {

    String agentType();

    StageResult execute(StageContext context) throws Exception;
}
