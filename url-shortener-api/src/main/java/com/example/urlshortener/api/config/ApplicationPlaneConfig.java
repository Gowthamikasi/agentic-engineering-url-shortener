package com.example.urlshortener.api.config;

import com.example.urlshortener.api.security.ApiKeyFilter;
import com.example.urlshortener.api.security.ApiKeyRegistry;
import com.example.urlshortener.api.security.RateLimitFilter;
import com.example.urlshortener.api.security.SecurityHeadersFilter;
import com.example.urlshortener.domain.Alphabet;
import com.example.urlshortener.domain.ShortCodeGenerator;
import com.example.urlshortener.domain.ShortCodeValidator;
import com.example.urlshortener.domain.UrlValidator;
import com.example.urlshortener.domain.spi.InetAddressHostResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.EnumSet;

/**
 * Wires the application plane.
 *
 * <p>Filter order is significant: security headers are set first so they are present even on a
 * rejected request, authentication runs next so the rate limiter can bucket by key id rather than
 * by address, and the rate limiter runs last before the controllers.
 */
@Configuration
@EnableConfigurationProperties(UrlShortenerProperties.class)
public class ApplicationPlaneConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public UrlValidator urlValidator(UrlShortenerProperties properties) {
        return new UrlValidator(new InetAddressHostResolver(), properties.getValidation().isEnforceAddressChecks());
    }

    /** Mints with the configured alphabet only; see {@link #shortCodeValidator()} for lookup. */
    @Bean
    public ShortCodeGenerator shortCodeGenerator(UrlShortenerProperties properties) {
        return new ShortCodeGenerator(
                Alphabet.fromConfig(properties.getShortCode().getAlphabet()),
                properties.getShortCode().getLength(),
                new SecureRandom());
    }

    /**
     * Lookup accepts every alphabet the system has ever minted with, which is what keeps links
     * created before an alphabet change resolvable after it.
     */
    @Bean
    public ShortCodeValidator shortCodeValidator() {
        return new ShortCodeValidator(EnumSet.allOf(Alphabet.class), 1, 16);
    }

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeyRegistry registry, ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(registry, objectMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(UrlShortenerProperties properties,
                                                                   ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties.getRateLimit(), objectMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }
}
