package com.example.urlshortener.domain;

/** Stable machine-readable error codes surfaced in RFC 9457 problem bodies. */
public final class ErrorCodes {

    public static final String URL_MALFORMED = "URL_MALFORMED";
    public static final String URL_SCHEME_NOT_ALLOWED = "URL_SCHEME_NOT_ALLOWED";
    public static final String URL_TOO_LONG = "URL_TOO_LONG";
    public static final String URL_CREDENTIALS_NOT_ALLOWED = "URL_CREDENTIALS_NOT_ALLOWED";
    public static final String URL_HOST_BLOCKED = "URL_HOST_BLOCKED";
    public static final String URL_HOST_UNRESOLVABLE = "URL_HOST_UNRESOLVABLE";
    public static final String EXPIRY_IN_PAST = "EXPIRY_IN_PAST";
    public static final String LINK_NOT_FOUND = "LINK_NOT_FOUND";
    public static final String LINK_EXPIRED = "LINK_EXPIRED";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String CODE_GENERATION_EXHAUSTED = "CODE_GENERATION_EXHAUSTED";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String PERSISTENCE_UNAVAILABLE = "PERSISTENCE_UNAVAILABLE";

    private ErrorCodes() {
    }
}
