package com.example.urlshortener.orchestration.engine;

/**
 * A stage executor.
 *
 * <p>Agents here are deterministic rather than LLM-backed (ASM-001), so tests repeat and the
 * evidence in this repository is reproducible. This interface is where an LLM adapter would go.
 *
 * <p>Implementations must be idempotent for the same inputs and must not retry internally; the
 * scheduler owns retry.
 */
public interface StageAgent {

    String agentType();

    StageResult execute(StageContext context) throws Exception;
}
