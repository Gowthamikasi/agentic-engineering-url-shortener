package com.example.urlshortener.api.security;

import com.example.urlshortener.api.config.UrlShortenerProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a presented {@code X-Api-Key} to the principal it authenticates.
 *
 * <p>Configuration holds SHA-256 hashes, never the keys themselves, and comparison uses
 * {@link MessageDigest#isEqual} so a wrong key takes the same time to reject whichever byte
 * differs. Candidates are compared against every configured key rather than short-circuiting
 * on the first mismatch, for the same reason.
 */
@Component
public class ApiKeyRegistry {

    /** What a key is allowed to do. Control-plane access is separate from application writes. */
    public enum Scope {
        READ,
        WRITE,
        CONTROL
    }

    /** An authenticated caller. Carries the key's id for audit, never the key. */
    public record Principal(String keyId, Set<Scope> scopes) {

        public boolean has(Scope scope) {
            return scopes.contains(scope);
        }
    }

    private final List<UrlShortenerProperties.ApiKey> configured;

    public ApiKeyRegistry(UrlShortenerProperties properties) {
        this.configured = List.copyOf(properties.getApiKeys());
    }

    public boolean isEmpty() {
        return configured.isEmpty();
    }

    public Optional<Principal> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        byte[] presentedHash = sha256(presentedKey.strip());

        Principal match = null;
        for (UrlShortenerProperties.ApiKey candidate : configured) {
            byte[] expected = decodeHex(candidate.getSha256());
            // No early exit: every configured key is compared so timing does not leak which matched.
            if (expected != null && MessageDigest.isEqual(presentedHash, expected)) {
                match = new Principal(candidate.getId(), parseScopes(candidate.getScopes()));
            }
        }
        return Optional.ofNullable(match);
    }

    public static String hashOf(String key) {
        return HexFormat.of().formatHex(sha256(key));
    }

    private static Set<Scope> parseScopes(List<String> raw) {
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        for (String value : raw) {
            try {
                scopes.add(Scope.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // An unknown scope name grants nothing rather than everything.
            }
        }
        return scopes;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static byte[] decodeHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(hex.strip().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
