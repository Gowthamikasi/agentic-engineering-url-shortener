package com.example.urlshortener.domain;

/**
 * Short-code alphabets (ADR-004, extended by ADR-017).
 *
 * <p>BASE58 drops the glyphs that look alike: 0, O, I and l. New codes are minted from one
 * alphabet; lookup accepts both, so codes made before a switch still resolve.
 */
public enum Alphabet {

    BASE62("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"),
    BASE58("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz");

    private final String characters;

    Alphabet(String characters) {
        this.characters = characters;
    }

    public String characters() {
        return characters;
    }

    public int size() {
        return characters.length();
    }

    public char charAt(int index) {
        return characters.charAt(index);
    }

    public boolean accepts(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (characters.indexOf(candidate.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    public static Alphabet fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return BASE62;
        }
        return Alphabet.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
