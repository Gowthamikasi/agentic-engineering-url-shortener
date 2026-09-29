package com.example.urlshortener.orchestration.model;

/** Whether a node waits for all of its dependencies or for any one of them. */
public enum JoinType {
    ALL,
    ANY
}
