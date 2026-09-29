package com.example.urlshortener.domain.error;

/** Base type for domain-level failures that map to a specific HTTP status and error code. */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
