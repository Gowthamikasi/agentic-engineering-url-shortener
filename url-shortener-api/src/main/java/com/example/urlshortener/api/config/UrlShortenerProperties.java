package com.example.urlshortener.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Externalised configuration for the application plane. */
@ConfigurationProperties(prefix = "urlshortener")
public class UrlShortenerProperties {

    /** Base URL used to build the {@code shortUrl} in responses. */
    private String baseUrl = "http://localhost:8080";

    private final ShortCode shortCode = new ShortCode();
    private final Validation validation = new Validation();
    private final RateLimit rateLimit = new RateLimit();
    private List<ApiKey> apiKeys = new ArrayList<>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public ShortCode getShortCode() {
        return shortCode;
    }

    public Validation getValidation() {
        return validation;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public List<ApiKey> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<ApiKey> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public static class ShortCode {

        /** Alphabet used to mint <em>new</em> codes. Lookup always accepts every known alphabet. */
        private String alphabet = "BASE62";
        private int length = 7;
        /** Bounded collision retry (ASM-003); exceeding it is a permanent failure, not an endless loop. */
        private int collisionRetries = 3;

        public String getAlphabet() {
            return alphabet;
        }

        public void setAlphabet(String alphabet) {
            this.alphabet = alphabet;
        }

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }

        public int getCollisionRetries() {
            return collisionRetries;
        }

        public void setCollisionRetries(int collisionRetries) {
            this.collisionRetries = collisionRetries;
        }
    }

    public static class Validation {

        /**
         * Whether target hosts are resolved and checked against the private-address denylist.
         * Only ever disabled in tests, which must not depend on DNS.
         */
        private boolean enforceAddressChecks = true;

        public boolean isEnforceAddressChecks() {
            return enforceAddressChecks;
        }

        public void setEnforceAddressChecks(boolean enforceAddressChecks) {
            this.enforceAddressChecks = enforceAddressChecks;
        }
    }

    public static class RateLimit {

        private boolean enabled = true;
        /** Create requests per minute, per API key (ASM-007). */
        private int createPerMinutePerKey = 60;
        /** Redirects per minute, per client address (ASM-007). */
        private int redirectPerMinutePerIp = 600;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCreatePerMinutePerKey() {
            return createPerMinutePerKey;
        }

        public void setCreatePerMinutePerKey(int createPerMinutePerKey) {
            this.createPerMinutePerKey = createPerMinutePerKey;
        }

        public int getRedirectPerMinutePerIp() {
            return redirectPerMinutePerIp;
        }

        public void setRedirectPerMinutePerIp(int redirectPerMinutePerIp) {
            this.redirectPerMinutePerIp = redirectPerMinutePerIp;
        }
    }

    /** One configured API key. Only the SHA-256 hash is ever held, never the key itself. */
    public static class ApiKey {

        private String id;
        private String sha256;
        private List<String> scopes = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSha256() {
            return sha256;
        }

        public void setSha256(String sha256) {
            this.sha256 = sha256;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes;
        }
    }
}
