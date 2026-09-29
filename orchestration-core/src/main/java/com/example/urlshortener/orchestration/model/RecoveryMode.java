package com.example.urlshortener.orchestration.model;

/**
 * What can be undone after a node fails.
 *
 * <p>ROLLBACKABLE: the output can be reverted or regenerated. COMPENSATABLE: something was
 * already observed outside the engine, so the best we can do is record a compensating action.
 * NONE: neither applies and a human has to decide.
 */
public enum RecoveryMode {
    ROLLBACKABLE,
    COMPENSATABLE,
    NONE
}
