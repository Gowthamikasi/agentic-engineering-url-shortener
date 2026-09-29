package com.example.urlshortener.api.web.dto;

import com.example.urlshortener.domain.model.LinkStats;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Click statistics for one code. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatsResponse(String code, long totalClicks, Instant lastClickAt,
                            List<DailyBucket> daily, String consistency) {

    /** Analytics are written off the redirect path, so the numbers trail reality slightly (ASM-005). */
    public static final String CONSISTENCY_NOTE = "eventual (<=1s)";

    public record DailyBucket(LocalDate date, long clicks) {
    }

    public static StatsResponse of(LinkStats stats) {
        return new StatsResponse(stats.code(), stats.totalClicks(), stats.lastClickAt(),
                stats.daily().stream().map(d -> new DailyBucket(d.date(), d.clicks())).toList(),
                CONSISTENCY_NOTE);
    }
}
