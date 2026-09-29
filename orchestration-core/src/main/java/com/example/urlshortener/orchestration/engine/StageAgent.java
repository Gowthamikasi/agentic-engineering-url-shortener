package com.example.urlshortener.orchestration.engine;

/**
 * A stage executor.
 *
 * <p>ASM-001: agents here are deterministic rather than LLM-backed, so every test is repeatable
 * and every piece of evidence in this repository is reproducible. The interface is the seam an
 * LLM-backed implementation would sit behind; that substitution is explicitly out of scope and
 * labelled as such rather than half-built.
 *
 * <p>Implementations must be idempotent for the same {@code (nodeId, inputs)} and must not retry
 * internally — the scheduler is the only retry authority.
 */
public interface StageAgent {

    String agentType();

    StageResult execute(StageContext context) throws Exception;
}
