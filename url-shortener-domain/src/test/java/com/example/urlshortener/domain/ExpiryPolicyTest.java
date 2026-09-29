package com.example.urlshortener.domain;

import com.example.urlshortener.domain.model.ShortLink;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private static ShortLink linkExpiringAt(Instant expiresAt) {
        return new ShortLink("k3Xz9Qa", URI.create("https://example.org/"), NOW.minusSeconds(60), expiresAt, null, "key-1");
    }

    @Test
    void a_link_without_an_expiry_never_expires() {
        assertThat(ExpiryPolicy.resolve(linkExpiringAt(null), NOW.plusSeconds(999_999)))
                .isEqualTo(ExpiryPolicy.Resolution.ACTIVE);
    }

    @Test
    void a_link_is_active_strictly_before_its_expiry() {
        assertThat(ExpiryPolicy.resolve(linkExpiringAt(NOW.plusSeconds(1)), NOW))
                .isEqualTo(ExpiryPolicy.Resolution.ACTIVE);
    }

    @Test
    void a_link_is_gone_at_and_after_its_expiry_instant() {
        ShortLink link = linkExpiringAt(NOW);

        assertThat(ExpiryPolicy.resolve(link, NOW)).isEqualTo(ExpiryPolicy.Resolution.GONE);
        assertThat(ExpiryPolicy.resolve(link, NOW.plusMillis(1))).isEqualTo(ExpiryPolicy.Resolution.GONE);
    }

    @Test
    void an_expiry_in_the_past_is_not_acceptable_at_creation_time() {
        assertThat(ExpiryPolicy.isAcceptableExpiry(NOW.minusSeconds(1), NOW)).isFalse();
        assertThat(ExpiryPolicy.isAcceptableExpiry(NOW, NOW)).isFalse();
        assertThat(ExpiryPolicy.isAcceptableExpiry(NOW.plusSeconds(1), NOW)).isTrue();
        assertThat(ExpiryPolicy.isAcceptableExpiry(null, NOW)).isTrue();
    }
}
