package com.example.urlshortener.orchestration.model;

/**
 * Why a node failed, which decides whether retrying is sensible.
 *
 * <p>Unknown causes are classified {@code PERMANENT} on purpose: retrying something the engine
 * does not understand risks repeating a harmful action, while refusing to retry it only costs a
 * human decision.
 */
public enum FailureClass {
    TRANSIENT,
    PERMANENT
}
