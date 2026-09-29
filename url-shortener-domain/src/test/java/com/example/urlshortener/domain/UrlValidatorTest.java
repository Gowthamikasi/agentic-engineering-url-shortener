package com.example.urlshortener.domain;

import com.example.urlshortener.domain.spi.HostResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class UrlValidatorTest {

    /** Resolves every host to one fixed address so the tests never touch DNS. */
    private static HostResolver resolvingTo(String literal) {
        return host -> new InetAddress[]{InetAddress.getByName(literal)};
    }

    private final UrlValidator publicHost = new UrlValidator(resolvingTo("93.184.216.34"), true);

    @Test
    void accepts_a_plain_https_url() {
        UrlValidationResult result = publicHost.validate("https://example.org/docs/getting-started");

        assertThat(result.valid()).isTrue();
        assertThat(result.canonical()).hasToString("https://example.org/docs/getting-started");
    }

    @Test
    void canonicalises_scheme_host_case_and_default_port() {
        UrlValidationResult result = publicHost.validate("HTTPS://Example.ORG:443/a?b=c");

        assertThat(result.valid()).isTrue();
        assertThat(result.canonical()).hasToString("https://example.org/a?b=c");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///etc/passwd", "ftp://example.org/x"})
    void rejects_schemes_outside_the_allowlist(String url) {
        UrlValidationResult result = publicHost.validate(url);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isIn(ErrorCodes.URL_SCHEME_NOT_ALLOWED, ErrorCodes.URL_MALFORMED);
    }

    @Test
    void rejects_embedded_credentials() {
        UrlValidationResult result = publicHost.validate("https://user:secret@example.org/");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCodes.URL_CREDENTIALS_NOT_ALLOWED);
    }

    @Test
    void rejects_urls_over_the_length_cap() {
        String longUrl = "https://example.org/" + "a".repeat(UrlValidator.MAX_URL_LENGTH);

        assertThat(publicHost.validate(longUrl).errorCode()).isEqualTo(ErrorCodes.URL_TOO_LONG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "192.168.0.7", "172.16.4.5", "169.254.1.1", "0.0.0.0", "100.100.0.1"})
    void rejects_hosts_that_resolve_to_non_public_addresses(String address) {
        UrlValidator validator = new UrlValidator(resolvingTo(address), true);

        UrlValidationResult result = validator.validate("https://sneaky.example/");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCodes.URL_HOST_BLOCKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://localhost/x", "https://db.internal/x", "https://printer.local/x"})
    void rejects_internal_host_names_without_resolving_them(String url) {
        UrlValidator validator = new UrlValidator(host -> {
            throw new AssertionError("resolution must not be attempted for denylisted names");
        }, true);

        assertThat(validator.validate(url).errorCode()).isEqualTo(ErrorCodes.URL_HOST_BLOCKED);
    }

    @Test
    void rejects_a_host_that_cannot_be_resolved() {
        UrlValidator validator = new UrlValidator(host -> {
            throw new UnknownHostException(host);
        }, true);

        assertThat(validator.validate("https://nope.example/").errorCode())
                .isEqualTo(ErrorCodes.URL_HOST_UNRESOLVABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "/relative/only", "https://", "http://exa mple.org"})
    void rejects_malformed_input(String url) {
        assertThat(publicHost.validate(url).valid()).isFalse();
    }

    @Test
    void rejects_control_characters() {
        assertThat(publicHost.validate("https://example.org/\u0000evil").errorCode())
                .isEqualTo(ErrorCodes.URL_MALFORMED);
    }

    @Test
    void rejects_the_data_scheme() {
        UrlValidationResult result = publicHost.validate("data:text/html,hello");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isIn(ErrorCodes.URL_SCHEME_NOT_ALLOWED, ErrorCodes.URL_MALFORMED);
    }
}
