package com.example.urlshortener.orchestration.model;

/** Why a node failed, which decides whether a retry makes sense. */
public enum FailureClass {
    TRANSIENT,
    PERMANENT
}
