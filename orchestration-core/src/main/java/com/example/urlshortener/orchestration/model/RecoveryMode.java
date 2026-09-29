package com.example.urlshortener.orchestration.model;

/** What can be undone after a node fails. */
public enum RecoveryMode {
    ROLLBACKABLE,
    COMPENSATABLE,
    NONE
}
