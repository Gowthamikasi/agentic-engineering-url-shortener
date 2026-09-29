package com.example.urlshortener.api.web;

import com.example.urlshortener.api.application.LinkQueryService;
import com.example.urlshortener.domain.model.ShortLink;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The public redirect endpoint, and the only route that serves anonymous traffic.
 *
 * <p>302 rather than 301: a permanent redirect gets cached, and a click that never reaches
 * the server cannot be counted.
 *
 * <p>The Location value comes from the stored target, never from the request, so this cannot
 * be used as an open redirector.
 */
@RestController
public class RedirectController {

    private final LinkQueryService queries;

    public RedirectController(LinkQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/{code:[A-Za-z0-9]{1,16}}")
    public ResponseEntity<Void> redirect(
            @PathVariable String code,
            @RequestHeader(value = "Referer", required = false) String referer,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        ShortLink link = queries.resolveForRedirect(code, referer, userAgent);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.target().toString()))
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store")
                .build();
    }
}
