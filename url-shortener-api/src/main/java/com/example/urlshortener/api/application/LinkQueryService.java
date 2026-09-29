package com.example.urlshortener.api.application;

import com.example.urlshortener.domain.ExpiryPolicy;
import com.example.urlshortener.domain.ShortCodeValidator;
import com.example.urlshortener.domain.error.LinkExpiredException;
import com.example.urlshortener.domain.error.LinkNotFoundException;
import com.example.urlshortener.domain.model.ClickEvent;
import com.example.urlshortener.domain.model.LinkStats;
import com.example.urlshortener.domain.model.ShortLink;
import com.example.urlshortener.domain.port.ClickRecorder;
import com.example.urlshortener.domain.port.ClickRepository;
import com.example.urlshortener.domain.port.ShortLinkRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/** Reads: resolve for redirect, fetch, delete, and aggregate clicks. */
@Service
public class LinkQueryService {

    private final ShortLinkRepository links;
    private final ClickRepository clicks;
    private final ClickRecorder clickRecorder;
    private final ShortCodeValidator codeValidator;
    private final Clock clock;

    public LinkQueryService(ShortLinkRepository links,
                            ClickRepository clicks,
                            ClickRecorder clickRecorder,
                            ShortCodeValidator codeValidator,
                            Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.clickRecorder = clickRecorder;
        this.codeValidator = codeValidator;
        this.clock = clock;
    }

    /**
     * Resolves a code for redirect.
     *
     * @throws LinkNotFoundException when no such code exists
     * @throws LinkExpiredException  when the link existed and has lapsed (410, not 404)
     */
    public ShortLink resolveForRedirect(String code, String referer, String userAgent) {
        ShortLink link = requireLink(code);
        if (ExpiryPolicy.resolve(link, clock.instant()) == ExpiryPolicy.Resolution.GONE) {
            throw new LinkExpiredException(code);
        }
        // Offer, never block. A full queue drops the count rather than slowing the redirect.
        clickRecorder.record(new ClickEvent(code, clock.instant(), refererHost(referer), classifyUserAgent(userAgent)));
        return link;
    }

    public ShortLink find(String code) {
        return requireLink(code);
    }

    public boolean delete(String code) {
        return links.deleteByCode(code);
    }

    /** Statistics stay readable after a link expires; only the redirect stops working. */
    public LinkStats stats(String code, Instant from, Instant to) {
        requireLink(code);
        return clicks.statsFor(code, from, to);
    }

    private ShortLink requireLink(String code) {
        if (!codeValidator.isWellFormed(code)) {
            throw new LinkNotFoundException(code);
        }
        return links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    /** Keep the host only. */
    static String refererHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            String host = java.net.URI.create(referer.strip()).getHost();
            return host == null || host.length() > 255 ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Coarse bucket only. */
    static String classifyUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return ClickEvent.UA_UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider") || ua.contains("curl")) {
            return ClickEvent.UA_BOT;
        }
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) {
            return ClickEvent.UA_MOBILE;
        }
        return ClickEvent.UA_DESKTOP;
    }
}
