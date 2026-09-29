package com.example.urlshortener.domain;

import com.example.urlshortener.domain.spi.HostResolver;
import com.example.urlshortener.domain.spi.InetAddressHostResolver;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and canonicalises a target URL before it is stored (ASM-014).
 *
 * <p>Checks run in order: syntax, scheme allowlist, length, embedded credentials, host
 * denylist, then every address the host resolves to.
 *
 * <p>Resolution happens at create time, so a host pointing somewhere private never gets a short
 * code and there is nothing to follow later.
 */
public final class UrlValidator {

    public static final int MAX_URL_LENGTH = 2048;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final List<String> BLOCKED_HOST_SUFFIXES = List.of(".local", ".internal", ".localdomain", ".home.arpa");
    private static final Set<String> BLOCKED_HOST_NAMES = Set.of("localhost");

    private final HostResolver hostResolver;
    private final boolean enforceAddressChecks;

    public UrlValidator() {
        this(new InetAddressHostResolver(), true);
    }

    public UrlValidator(HostResolver hostResolver, boolean enforceAddressChecks) {
        this.hostResolver = hostResolver;
        this.enforceAddressChecks = enforceAddressChecks;
    }

    public UrlValidationResult validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return UrlValidationResult.rejected(ErrorCodes.URL_MALFORMED, "URL must not be empty.");
        }
        String candidate = raw.strip();
        if (candidate.length() > MAX_URL_LENGTH) {
            return UrlValidationResult.rejected(ErrorCodes.URL_TOO_LONG,
                    "URL exceeds the maximum of " + MAX_URL_LENGTH + " characters.");
        }
        if (containsControlCharacter(candidate)) {
            return UrlValidationResult.rejected(ErrorCodes.URL_MALFORMED, "URL contains control characters.");
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return UrlValidationResult.rejected(ErrorCodes.URL_MALFORMED, "URL is not a valid URI: " + e.getReason());
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return UrlValidationResult.rejected(ErrorCodes.URL_MALFORMED, "URL must be absolute and include a scheme.");
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return UrlValidationResult.rejected(ErrorCodes.URL_SCHEME_NOT_ALLOWED,
                    "Scheme '" + scheme + "' is not allowed; only http and https are accepted.");
        }
        if (uri.getUserInfo() != null) {
            return UrlValidationResult.rejected(ErrorCodes.URL_CREDENTIALS_NOT_ALLOWED,
                    "URLs carrying embedded credentials are rejected.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return UrlValidationResult.rejected(ErrorCodes.URL_MALFORMED, "URL must include a host.");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOST_NAMES.contains(lowerHost)
                || BLOCKED_HOST_SUFFIXES.stream().anyMatch(lowerHost::endsWith)) {
            return UrlValidationResult.rejected(ErrorCodes.URL_HOST_BLOCKED,
                    "Host '" + host + "' is an internal name and is not allowed as a redirect target.");
        }

        if (enforceAddressChecks) {
            UrlValidationResult addressCheck = checkAddresses(host);
            if (addressCheck != null) {
                return addressCheck;
            }
        }

        return UrlValidationResult.ok(canonicalise(uri, scheme, lowerHost));
    }

    private UrlValidationResult checkAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException e) {
            return UrlValidationResult.rejected(ErrorCodes.URL_HOST_UNRESOLVABLE,
                    "Host '" + host + "' could not be resolved.");
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                return UrlValidationResult.rejected(ErrorCodes.URL_HOST_BLOCKED,
                        "Host resolves to a private, loopback or otherwise non-public address.");
            }
        }
        return null;
    }

    /** Refuses loopback, link-local, RFC1918, multicast, wildcard, IPv6 unique-local and CGNAT. */
    public static boolean isNonPublic(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet6Address) {
            // fc00::/7 unique local addresses
            return (raw[0] & 0xFE) == 0xFC;
        }
        int first = raw[0] & 0xFF;
        int second = raw[1] & 0xFF;
        // 100.64.0.0/10 carrier-grade NAT, and 169.254/16 is already covered by isLinkLocalAddress
        return first == 100 && second >= 64 && second <= 127;
    }

    /** Lower-cases scheme and host and drops a default port. Path and query are left alone. */
    private static URI canonicalise(URI uri, String scheme, String lowerHost) {
        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        try {
            return new URI(scheme, null, lowerHost, port, uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7F);
    }
}
