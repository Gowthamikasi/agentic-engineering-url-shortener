package com.example.urlshortener.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeTest {

    @Test
    void base58_excludes_visually_ambiguous_glyphs() {
        assertThat(Alphabet.BASE58.characters())
                .doesNotContain("0")
                .doesNotContain("O")
                .doesNotContain("I")
                .doesNotContain("l");
        assertThat(Alphabet.BASE58.size()).isEqualTo(58);
        assertThat(Alphabet.BASE62.size()).isEqualTo(62);
    }

    @Test
    void generator_mints_codes_of_the_configured_length_from_its_alphabet() {
        ShortCodeGenerator generator = new ShortCodeGenerator(Alphabet.BASE58);

        for (int i = 0; i < 500; i++) {
            String code = generator.next();
            assertThat(code).hasSize(ShortCodeGenerator.DEFAULT_LENGTH);
            assertThat(Alphabet.BASE58.accepts(code)).isTrue();
        }
    }

    @Test
    void generator_output_is_not_trivially_repeating() {
        ShortCodeGenerator generator = new ShortCodeGenerator(Alphabet.BASE62);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 2000; i++) {
            seen.add(generator.next());
        }

        assertThat(seen).hasSizeGreaterThan(1990);
    }

    @Test
    void generator_is_deterministic_for_a_seeded_source_so_tests_can_pin_collisions() {
        ShortCodeGenerator a = new ShortCodeGenerator(Alphabet.BASE62, 7, new Random(42));
        ShortCodeGenerator b = new ShortCodeGenerator(Alphabet.BASE62, 7, new Random(42));

        assertThat(a.next()).isEqualTo(b.next());
    }

    @Test
    void lookup_validator_accepts_legacy_base62_codes_after_the_base58_switch() {
        ShortCodeValidator validator = new ShortCodeValidator();

        assertThat(validator.isWellFormed("k3Xz9Qa")).isTrue();
        assertThat(validator.isWellFormed("0OIl123")).isTrue();
        assertThat(validator.isWellFormed("abc-def")).isFalse();
        assertThat(validator.isWellFormed("")).isFalse();
        assertThat(validator.isWellFormed(null)).isFalse();
    }

    @Test
    void alphabet_is_selectable_by_configuration_string() {
        assertThat(Alphabet.fromConfig("base58")).isEqualTo(Alphabet.BASE58);
        assertThat(Alphabet.fromConfig(" BASE62 ")).isEqualTo(Alphabet.BASE62);
        assertThat(Alphabet.fromConfig(null)).isEqualTo(Alphabet.BASE62);
    }
}
