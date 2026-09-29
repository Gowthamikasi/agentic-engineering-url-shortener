package com.example.urlshortener.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Aggregated click statistics for one short code. */
public record LinkStats(String code, long totalClicks, Instant lastClickAt, List<DailyClicks> daily) {

    /** Clicks bucketed by UTC calendar day. */
    public record DailyClicks(LocalDate date, long clicks) {
    }
}
