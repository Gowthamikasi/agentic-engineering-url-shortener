package com.example.urlshortener.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over the canonical request payload, used to detect Idempotency-Key body mismatches. */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** Canonical form of a create-link request: target URL plus expiry, both normalised. */
    public static String createLinkPayload(String url, String expiresAt) {
        return (url == null ? "" : url.strip()) + "|" + (expiresAt == null ? "" : expiresAt.strip());
    }
}
