package com.example.urlshortener.api.web;

import com.example.urlshortener.api.application.CreateLinkService;
import com.example.urlshortener.api.application.LinkQueryService;
import com.example.urlshortener.api.config.UrlShortenerProperties;
import com.example.urlshortener.api.security.ApiKeyFilter;
import com.example.urlshortener.api.security.ApiKeyRegistry;
import com.example.urlshortener.api.web.dto.CreateLinkRequest;
import com.example.urlshortener.api.web.dto.LinkResponse;
import com.example.urlshortener.api.web.dto.StatsResponse;
import com.example.urlshortener.contracts.ApiContract;
import com.example.urlshortener.domain.ErrorCodes;
import com.example.urlshortener.domain.error.InvalidRequestException;
import com.example.urlshortener.domain.error.LinkNotFoundException;
import com.example.urlshortener.domain.model.ShortLink;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Application-plane link management. */
@RestController
@RequestMapping("/api/v1/links")
public class LinksController {

    /** Declared once, in the contracts module, so both planes agree on the published version. */
    public static final String API_VERSION = ApiContract.VERSION;

    private final CreateLinkService createLink;
    private final LinkQueryService queries;
    private final UrlShortenerProperties properties;
    private final Clock clock;

    public LinksController(CreateLinkService createLink, LinkQueryService queries,
                           UrlShortenerProperties properties, Clock clock) {
        this.createLink = createLink;
        this.queries = queries;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(
            @Valid @RequestBody CreateLinkRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {

        CreateLinkService.Outcome outcome = createLink.create(new CreateLinkService.Command(
                request.url(),
                parseExpiry(request.expiresAt()),
                request.expiresAt(),
                idempotencyKey,
                keyIdOf(httpRequest)));

        LinkResponse body = LinkResponse.of(outcome.link(), properties.getBaseUrl());
        // A replay is not a new creation, so it answers 200 rather than 201.
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header(ApiContract.HEADER, API_VERSION)
                .location(URI.create("/api/v1/links/" + outcome.link().code()))
                .body(body);
    }

    @GetMapping("/{code}")
    public ResponseEntity<LinkResponse> get(@PathVariable String code) {
        ShortLink link = queries.find(code);
        return ResponseEntity.ok()
                .header(ApiContract.HEADER, API_VERSION)
                .body(LinkResponse.of(link, properties.getBaseUrl()));
    }

    /** Statistics stay readable after a link expires; only the redirect stops. */
    @GetMapping("/{code}/stats")
    public ResponseEntity<StatsResponse> stats(
            @PathVariable String code,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {

        Instant now = clock.instant();
        Instant fromInstant = from == null ? now.minus(Duration.ofDays(30)) : parseWindowBound(from, "from");
        Instant toInstant = to == null ? now.plus(Duration.ofDays(1)) : parseWindowBound(to, "to");
        if (!fromInstant.isBefore(toInstant)) {
            throw new InvalidRequestException(ErrorCodes.URL_MALFORMED, "'from' must be before 'to'.");
        }

        return ResponseEntity.ok()
                .header(ApiContract.HEADER, API_VERSION)
                .body(StatsResponse.of(queries.stats(code, fromInstant, toInstant)));
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        if (!queries.delete(code)) {
            throw new LinkNotFoundException(code);
        }
        return ResponseEntity.noContent().header(ApiContract.HEADER, API_VERSION).build();
    }

    private static Instant parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(ErrorCodes.URL_MALFORMED,
                    "expiresAt must be an ISO-8601 UTC instant, for example 2026-12-31T23:59:59Z.");
        }
    }

    private static Instant parseWindowBound(String raw, String field) {
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(ErrorCodes.URL_MALFORMED,
                    "'" + field + "' must be an ISO-8601 UTC instant.");
        }
    }

    private static String keyIdOf(HttpServletRequest request) {
        Object principal = request.getAttribute(ApiKeyFilter.PRINCIPAL_ATTRIBUTE);
        return principal instanceof ApiKeyRegistry.Principal p ? p.keyId() : null;
    }
}
