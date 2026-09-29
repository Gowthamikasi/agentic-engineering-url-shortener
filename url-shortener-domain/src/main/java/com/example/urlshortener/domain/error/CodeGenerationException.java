package com.example.urlshortener.domain.error;

import com.example.urlshortener.domain.ErrorCodes;

/** Bounded collision retry was exhausted (ASM-003); maps to 503. */
public class CodeGenerationException extends DomainException {

    public CodeGenerationException(int attempts) {
        super(ErrorCodes.CODE_GENERATION_EXHAUSTED,
                "Could not mint a free short code after " + attempts + " attempts.");
    }
}
