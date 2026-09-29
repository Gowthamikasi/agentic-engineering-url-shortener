package com.example.urlshortener.infrastructure.adapter;

import com.example.urlshortener.domain.model.ClickEvent;
import com.example.urlshortener.domain.model.LinkStats;
import com.example.urlshortener.domain.port.ClickRepository;
import com.example.urlshortener.infrastructure.entity.ClickEntity;
import com.example.urlshortener.infrastructure.jpa.ClickJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Adapts the click port to Spring Data JPA, including the daily aggregation for the stats endpoint. */
@Repository
public class JpaClickRepository implements ClickRepository {

    private final ClickJpaRepository jpa;

    public JpaClickRepository(ClickJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public void saveAll(List<ClickEvent> events) {
        jpa.saveAll(events.stream()
                .map(e -> new ClickEntity(e.code(), e.occurredAt(), e.refererHost(), e.userAgentClass()))
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public LinkStats statsFor(String code, Instant from, Instant to) {
        long total = jpa.countInWindow(code, from, to);
        Instant lastClick = jpa.lastClickAt(code);

        // Bucketing in the JVM keeps the query portable across H2 and PostgreSQL, at the cost of
        // reading the window's rows. The demo windows are small; a production build would push
        // the date_trunc into SQL.
        Map<LocalDate, Long> byDay = new TreeMap<>();
        for (ClickEntity click : jpa.findInWindow(code, from, to)) {
            LocalDate day = click.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate();
            byDay.merge(day, 1L, Long::sum);
        }

        List<LinkStats.DailyClicks> daily = byDay.entrySet().stream()
                .map(e -> new LinkStats.DailyClicks(e.getKey(), e.getValue()))
                .toList();

        return new LinkStats(code, total, lastClick, daily);
    }

    @Override
    @Transactional(readOnly = true)
    public long countFor(String code) {
        return jpa.countByCode(code);
    }
}
