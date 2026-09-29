package com.example.urlshortener.domain;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * Mints random short codes: length 7, from SecureRandom (ASM-003).
 *
 * <p>The alphabet is injected rather than hard-coded, so it can be changed by configuration.
 */
public final class ShortCodeGenerator {

    public static final int DEFAULT_LENGTH = 7;

    private final Alphabet alphabet;
    private final int length;
    private final RandomGenerator random;

    public ShortCodeGenerator(Alphabet alphabet) {
        this(alphabet, DEFAULT_LENGTH, new SecureRandom());
    }

    public ShortCodeGenerator(Alphabet alphabet, int length, RandomGenerator random) {
        if (length < 1) {
            throw new IllegalArgumentException("short code length must be positive");
        }
        this.alphabet = alphabet;
        this.length = length;
        this.random = random;
    }

    public Alphabet alphabet() {
        return alphabet;
    }

    public String next() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.size())));
        }
        return sb.toString();
    }
}
