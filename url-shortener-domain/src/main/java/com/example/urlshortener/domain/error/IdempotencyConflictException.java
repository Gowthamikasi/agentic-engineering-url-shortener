package com.example.urlshortener.domain.error;

import com.example.urlshortener.domain.ErrorCodes;

/** The Idempotency-Key was reused with a different request body; maps to 409 (ASM-002). */
public class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException(String key) {
        super(ErrorCodes.IDEMPOTENCY_CONFLICT,
                "Idempotency-Key '" + key + "' was already used with a different request body.");
    }
}
