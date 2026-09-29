package com.example.urlshortener.orchestration.engine;

import java.time.Duration;

/**
 * Engine tuning.
 *
 * @param maxParallelism  how many node attempts may run at once
 * @param approvalTimeout how long a gate waits before safe-stopping (never before approving)
 * @param jitterFactor    proportion of random jitter applied to retry backoff, to avoid lockstep retries
 */
public record EngineSettings(int maxParallelism, Duration approvalTimeout, double jitterFactor) {

    public static EngineSettings defaults() {
        return new EngineSettings(4, Duration.ofHours(24), 0.2);
    }

    public EngineSettings {
        maxParallelism = maxParallelism <= 0 ? 1 : maxParallelism;
        approvalTimeout = approvalTimeout == null ? Duration.ofHours(24) : approvalTimeout;
        jitterFactor = jitterFactor < 0 ? 0 : Math.min(jitterFactor, 1.0);
    }
}
