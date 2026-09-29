package com.example.urlshortener.api.web.dto;

import com.example.urlshortener.domain.model.ShortLink;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Representation of a short link. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkResponse(String code, String shortUrl, String target, Instant createdAt, Instant expiresAt) {

    public static LinkResponse of(ShortLink link, String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return new LinkResponse(link.code(), base + "/" + link.code(), link.target().toString(),
                link.createdAt(), link.expiresAt());
    }
}
