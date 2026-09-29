package com.example.urlshortener.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Accepts a short code for lookup if any known alphabet accepts it.
 *
 * <p>Minting narrows to one alphabet, lookup stays wide. That asymmetry is what lets the alphabet
 * change without breaking codes already in the wild.
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
