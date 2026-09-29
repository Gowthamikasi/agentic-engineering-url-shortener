package com.example.urlshortener.orchestration.model;

/** Whether a node's failure blocks its downstream subgraph or merely degrades the run. */
public enum Criticality {
    BLOCKING,
    NON_BLOCKING
}
