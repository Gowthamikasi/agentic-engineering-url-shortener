package com.example.urlshortener.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

    @Test
    void the_same_logical_request_hashes_the_same_regardless_of_surrounding_whitespace() {
        String a = RequestHasher.sha256(RequestHasher.createLinkPayload(" https://example.org/x ", "2026-12-31T23:59:59Z"));
        String b = RequestHasher.sha256(RequestHasher.createLinkPayload("https://example.org/x", " 2026-12-31T23:59:59Z"));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void a_different_expiry_produces_a_different_hash() {
        String a = RequestHasher.sha256(RequestHasher.createLinkPayload("https://example.org/x", "2026-12-31T23:59:59Z"));
        String b = RequestHasher.sha256(RequestHasher.createLinkPayload("https://example.org/x", null));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashes_are_hex_sha256() {
        assertThat(RequestHasher.sha256("x")).hasSize(64).matches("[0-9a-f]+");
    }
}
