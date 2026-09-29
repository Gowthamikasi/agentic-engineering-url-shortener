package com.example.urlshortener.api.security;

import com.example.urlshortener.api.config.UrlShortenerProperties;
import com.example.urlshortener.domain.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Token-bucket rate limiting: creates per API key, redirects per client address. */
public class RateLimitFilter extends OncePerRequestFilter {

    private final UrlShortenerProperties.RateLimit config;
    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> createBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> redirectBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(UrlShortenerProperties.RateLimit config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = bucketFor(request);
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            rejectTooManyRequests(response, retryAfterSeconds);
            return;
        }
        response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
        chain.doFilter(request, response);
    }

    private Bucket bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && path.startsWith("/api/v1/links")) {
            String keyId = keyIdOf(request);
            return createBuckets.computeIfAbsent(keyId,
                    k -> newBucket(config.getCreatePerMinutePerKey()));
        }
        if ("GET".equals(request.getMethod()) && isRedirectPath(path)) {
            return redirectBuckets.computeIfAbsent(clientAddress(request),
                    k -> newBucket(config.getRedirectPerMinutePerIp()));
        }
        return null;
    }

    /** A redirect is a single non-API path segment, like /k3Xz9Qa. */
    private static boolean isRedirectPath(String path) {
        if (path == null || path.length() < 2 || path.indexOf('/', 1) >= 0) {
            return false;
        }
        return !path.startsWith("/api") && !path.startsWith("/actuator") && !path.startsWith("/health");
    }

    private static String keyIdOf(HttpServletRequest request) {
        Object principal = request.getAttribute(ApiKeyFilter.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof ApiKeyRegistry.Principal p) {
            return p.keyId();
        }
        return "anonymous";
    }

    private static String clientAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private static Bucket newBucket(int perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(perMinute, Refill.greedy(perMinute, Duration.ofMinutes(1))))
                .build();
    }

    private void rejectTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(URI.create("https://example/errors/rate-limited"));
        problem.setTitle("Too many requests");
        problem.setDetail("Rate limit exceeded. Retry after " + retryAfterSeconds + " second(s).");
        problem.setProperty("errorCode", ErrorCodes.RATE_LIMITED);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
