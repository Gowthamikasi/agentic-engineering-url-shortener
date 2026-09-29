package com.example.urlshortener.domain.error;

import com.example.urlshortener.domain.ErrorCodes;

/** The URL parses but policy refuses it (private host, unresolvable host); maps to 422. */
public class BlockedTargetException extends DomainException {

    public BlockedTargetException(String message) {
        super(ErrorCodes.URL_HOST_BLOCKED, message);
    }

    public BlockedTargetException(String errorCode, String message) {
        super(errorCode, message);
    }
}
