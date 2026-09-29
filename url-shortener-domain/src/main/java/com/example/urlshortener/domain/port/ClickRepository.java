package com.example.urlshortener.domain.port;

import com.example.urlshortener.domain.model.ClickEvent;
import com.example.urlshortener.domain.model.LinkStats;

import java.time.Instant;
import java.util.List;

/** Outbound port for click persistence and aggregation. */
public interface ClickRepository {

    void saveAll(List<ClickEvent> events);

    LinkStats statsFor(String code, Instant from, Instant to);

    long countFor(String code);
}
