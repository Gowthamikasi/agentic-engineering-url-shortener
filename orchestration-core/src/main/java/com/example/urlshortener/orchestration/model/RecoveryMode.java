package com.example.urlshortener.orchestration.model;

/**
 * What can be undone after a node fails.
 *
 * <p>{@code ROLLBACKABLE} means the node's output can be reverted to its prior version — a patch
 * un-applied, a report regenerated. {@code COMPENSATABLE} means something was already observed
 * outside the engine and cannot be taken back, so the only honest response is a recorded
 * compensating action. {@code NONE} means neither applies and a human must decide.
 */
public enum RecoveryMode {
    ROLLBACKABLE,
    COMPENSATABLE,
    NONE
}
