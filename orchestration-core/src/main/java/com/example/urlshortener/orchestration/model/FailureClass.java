package com.example.urlshortener.orchestration.model;

/**
 * Why a node failed, which decides whether a retry makes sense.
 *
 * <p>Unknown causes are treated as PERMANENT. Retrying something we do not understand could
 * repeat a side effect; refusing to retry only costs a human decision.
 */
public enum FailureClass {
    TRANSIENT,
    PERMANENT
}
