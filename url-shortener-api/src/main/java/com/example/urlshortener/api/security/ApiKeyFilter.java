package com.example.urlshortener.api.security;

import com.example.urlshortener.api.security.ApiKeyRegistry.Principal;
import com.example.urlshortener.api.security.ApiKeyRegistry.Scope;
import com.example.urlshortener.domain.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

/**
 * Authenticates {@code X-Api-Key} and enforces the scope each protected route needs.
 *
 * <p>The redirect endpoint is deliberately public — it is the one surface a shortener must expose
 * to anonymous traffic. Everything that creates data, reads another caller's data, or touches the
 * control plane requires a key.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String PRINCIPAL_ATTRIBUTE = "urlshortener.principal";

    private final ApiKeyRegistry registry;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(ApiKeyRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Scope required = requiredScope(request);
        if (required == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<Principal> principal = registry.authenticate(request.getHeader(HEADER));
        if (principal.isEmpty()) {
            reject(response, "A valid " + HEADER + " header is required for this endpoint.");
            return;
        }
        if (!principal.get().has(required)) {
            reject(response, "The presented API key lacks the " + required + " scope.");
            return;
        }

        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        chain.doFilter(request, response);
    }

    /** @return the scope this request needs, or {@code null} when the route is public. */
    static Scope requiredScope(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith("/api/v1/workflows") || path.startsWith("/api/v1/policy-exceptions")) {
            return Scope.CONTROL;
        }
        if (path.startsWith("/api/v1/metrics")) {
            return Scope.READ;
        }
        if (path.startsWith("/api/v1/links")) {
            return "GET".equals(method) ? Scope.READ : Scope.WRITE;
        }
        return null;
    }

    private void reject(HttpServletResponse response, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create("https://example/errors/unauthorized"));
        problem.setTitle("API key missing or invalid");
        problem.setDetail(detail);
        problem.setProperty("errorCode", ErrorCodes.UNAUTHORIZED);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
