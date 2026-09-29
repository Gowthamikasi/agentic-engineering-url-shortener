package com.example.urlshortener.domain;

/**
 * Short-code alphabet strategy (ADR-004, superseded by ADR-017 in the brownfield scenario).
 *
 * <p>BASE62 is the original alphabet. BASE58 drops the visually ambiguous glyphs {@code 0 O I l}.
 * The generator mints with one alphabet; the validator accepts both so legacy codes keep resolving.
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
