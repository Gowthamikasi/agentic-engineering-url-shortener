package com.example.urlshortener.api.application;

import com.example.urlshortener.api.config.UrlShortenerProperties;
import com.example.urlshortener.domain.ErrorCodes;
import com.example.urlshortener.domain.ExpiryPolicy;
import com.example.urlshortener.domain.RequestHasher;
import com.example.urlshortener.domain.ShortCodeGenerator;
import com.example.urlshortener.domain.UrlValidationResult;
import com.example.urlshortener.domain.UrlValidator;
import com.example.urlshortener.domain.error.BlockedTargetException;
import com.example.urlshortener.domain.error.CodeGenerationException;
import com.example.urlshortener.domain.error.IdempotencyConflictException;
import com.example.urlshortener.domain.error.InvalidRequestException;
import com.example.urlshortener.domain.model.IdempotencyRecord;
import com.example.urlshortener.domain.model.ShortLink;
import com.example.urlshortener.domain.port.IdempotencyRepository;
import com.example.urlshortener.domain.port.ShortLinkRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Creates short links: validate, check idempotency, then mint a code.
 *
 * <p>The order matters. Validating first means a bad URL is rejected the same way with or
 * without an Idempotency-Key, and checking idempotency before minting means a retried
 * request does not burn a second code.
 */
@Service
public class CreateLinkService {

    /** What the caller asked for. */
    public record Command(String url, Instant expiresAt, String rawExpiresAt, String idempotencyKey, String keyId) {
    }

    /** Whether this was a new link or a replay, so the controller can pick 201 or 200. */
    public record Outcome(ShortLink link, boolean replayed) {
    }

    private static final Set<String> BLOCKING_ERROR_CODES =
            Set.of(ErrorCodes.URL_HOST_BLOCKED, ErrorCodes.URL_HOST_UNRESOLVABLE);

    private final ShortLinkRepository links;
    private final IdempotencyRepository idempotency;
    private final UrlValidator urlValidator;
    private final ShortCodeGenerator generator;
    private final int collisionRetries;
    private final Clock clock;

    public CreateLinkService(ShortLinkRepository links,
                             IdempotencyRepository idempotency,
                             UrlValidator urlValidator,
                             ShortCodeGenerator generator,
                             UrlShortenerProperties properties,
                             Clock clock) {
        this.links = links;
        this.idempotency = idempotency;
        this.urlValidator = urlValidator;
        this.generator = generator;
        this.collisionRetries = properties.getShortCode().getCollisionRetries();
        this.clock = clock;
    }

    public Outcome create(Command command) {
        Instant now = clock.instant();

        UrlValidationResult validation = urlValidator.validate(command.url());
        if (!validation.valid()) {
            throw toException(validation);
        }
        if (!ExpiryPolicy.isAcceptableExpiry(command.expiresAt(), now)) {
            throw new InvalidRequestException(ErrorCodes.EXPIRY_IN_PAST,
                    "expiresAt must be strictly in the future.");
        }

        String requestHash = RequestHasher.sha256(
                RequestHasher.createLinkPayload(validation.canonical().toString(), command.rawExpiresAt()));

        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            Optional<Outcome> replay = replayIfSeen(command.idempotencyKey(), requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        ShortLink stored = mint(command, validation, now);

        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            idempotency.save(new IdempotencyRecord(command.idempotencyKey(), requestHash, stored.code(), now));
        }
        return new Outcome(stored, false);
    }

    /** Same key and body replays the original; same key with a different body is a conflict. */
    private Optional<Outcome> replayIfSeen(String idempotencyKey, String requestHash) {
        Optional<IdempotencyRecord> seen = idempotency.find(idempotencyKey);
        if (seen.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = seen.get();
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return links.findByCode(record.code()).map(link -> new Outcome(link, true));
    }

    /**
     * Mints a free code, giving up after a bounded number of collisions.
     *
     * <p>With a 62^7 space a collision is already unlikely, so repeated ones mean something is
     * wrong. Looping forever would turn that into a hang instead of an error.
     */
    private ShortLink mint(Command command, UrlValidationResult validation, Instant now) {
        for (int attempt = 1; attempt <= collisionRetries; attempt++) {
            ShortLink candidate = new ShortLink(generator.next(), validation.canonical(), now,
                    command.expiresAt(), command.idempotencyKey(), command.keyId());
            if (links.saveIfAbsent(candidate)) {
                return candidate;
            }
        }
        throw new CodeGenerationException(collisionRetries);
    }

    private static RuntimeException toException(UrlValidationResult validation) {
        if (BLOCKING_ERROR_CODES.contains(validation.errorCode())) {
            return new BlockedTargetException(validation.errorCode(), validation.detail());
        }
        return new InvalidRequestException(validation.errorCode(), validation.detail());
    }
}
