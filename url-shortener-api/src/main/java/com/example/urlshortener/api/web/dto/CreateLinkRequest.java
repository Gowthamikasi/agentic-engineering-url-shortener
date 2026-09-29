package com.example.urlshortener.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-link request body.
 *
 * <p>{@code expiresAt} is carried as a string rather than an {@code Instant} so a malformed value
 * produces a domain error with a stable error code, instead of a Jackson parse failure that would
 * never reach the validation rules.
 */
public record CreateLinkRequest(

        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url exceeds the maximum length of 2048 characters")
        String url,

        String expiresAt) {
}
