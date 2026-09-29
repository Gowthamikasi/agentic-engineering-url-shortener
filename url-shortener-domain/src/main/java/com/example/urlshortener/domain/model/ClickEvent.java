package com.example.urlshortener.domain.model;

import java.time.Instant;

/**
 * One redirect observation. Deliberately carries no PII (no IP, no full user agent, no full referer)
 * so the compliance policy "no PII persisted in clicks" can hold.
 *
 * @param code          the short code that was followed
 * @param occurredAt    when the redirect was served
 * @param refererHost   host part of the Referer header only, or {@code null}
 * @param userAgentClass coarse bucket: {@code bot}, {@code mobile}, {@code desktop}, {@code unknown}
 */
public record ClickEvent(String code, Instant occurredAt, String refererHost, String userAgentClass) {

    public static final String UA_BOT = "bot";
    public static final String UA_MOBILE = "mobile";
    public static final String UA_DESKTOP = "desktop";
    public static final String UA_UNKNOWN = "unknown";
}
