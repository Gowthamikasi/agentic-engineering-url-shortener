package com.example.urlshortener.domain.error;

/** The request is syntactically or semantically invalid; maps to 400. */
public class InvalidRequestException extends DomainException {

    public InvalidRequestException(String errorCode, String message) {
        super(errorCode, message);
    }
}
