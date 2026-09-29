package com.example.urlshortener.telemetry.audit;

/** Who caused an audited action. Every audit row must attribute one of these. */
public enum ActorType {
    HUMAN,
    AGENT,
    ENGINE,
    SYSTEM
}
