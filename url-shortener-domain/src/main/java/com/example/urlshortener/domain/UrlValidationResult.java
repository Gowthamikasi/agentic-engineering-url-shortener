package com.example.urlshortener.domain;

import java.net.URI;

/** Outcome of validating a candidate target URL. */
public record UrlValidationResult(boolean valid, URI canonical, String errorCode, String detail) {

    public static UrlValidationResult ok(URI canonical) {
        return new UrlValidationResult(true, canonical, null, null);
    }

    public static UrlValidationResult rejected(String errorCode, String detail) {
        return new UrlValidationResult(false, null, errorCode, detail);
    }
}
