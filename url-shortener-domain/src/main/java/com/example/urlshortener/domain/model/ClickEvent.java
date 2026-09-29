package com.example.urlshortener.domain.model;

import java.time.Instant;

/**
 * One redirect observation.
 *
 * <p>Deliberately holds no PII: no IP, no full user agent, no full referer. That is what lets the
 * compliance rule about click data hold.
 *
 * @param code           the short code that was followed
 * @param occurredAt     when the redirect was served
 * @param refererHost    host part of the Referer header only, or null
 * @param userAgentClass coarse bucket: bot, mobile, desktop or unknown
 */
public record ClickEvent(String code, Instant occurredAt, String refererHost, String userAgentClass) {

    public static final String UA_BOT = "bot";
    public static final String UA_MOBILE = "mobile";
    public static final String UA_DESKTOP = "desktop";
    public static final String UA_UNKNOWN = "unknown";
}
