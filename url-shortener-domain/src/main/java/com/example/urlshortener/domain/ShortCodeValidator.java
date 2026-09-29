package com.example.urlshortener.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Accepts a short code for <em>lookup</em> if any configured alphabet accepts it.
 *
 * <p>This is the compatibility seam that lets the brownfield change (mint base58) ship without
 * breaking links that were minted as base62: minting narrows, lookup stays wide.
 */
public final class ShortCodeValidator {

    private final Set<Alphabet> acceptedForLookup;
    private final int minLength;
    private final int maxLength;

    public ShortCodeValidator() {
        this(EnumSet.allOf(Alphabet.class), 1, 16);
    }

    public ShortCodeValidator(Set<Alphabet> acceptedForLookup, int minLength, int maxLength) {
        this.acceptedForLookup = EnumSet.copyOf(acceptedForLookup);
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    public boolean isWellFormed(String code) {
        if (code == null || code.length() < minLength || code.length() > maxLength) {
            return false;
        }
        return acceptedForLookup.stream().anyMatch(a -> a.accepts(code));
    }

    public Set<Alphabet> acceptedForLookup() {
        return EnumSet.copyOf(acceptedForLookup);
    }
}
