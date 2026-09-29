package com.example.urlshortener.infrastructure;

import com.example.urlshortener.domain.model.ClickEvent;
import com.example.urlshortener.domain.model.IdempotencyRecord;
import com.example.urlshortener.domain.model.LinkStats;
import com.example.urlshortener.domain.model.ShortLink;
import com.example.urlshortener.infrastructure.adapter.JpaClickRepository;
import com.example.urlshortener.infrastructure.adapter.JpaIdempotencyRepository;
import com.example.urlshortener.infrastructure.adapter.JpaShortLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaShortLinkRepository.class, JpaIdempotencyRepository.class, JpaClickRepository.class})
class ShortLinkPersistenceTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    @Autowired
    private JpaShortLinkRepository links;

    @Autowired
    private JpaIdempotencyRepository idempotency;

    @Autowired
    private JpaClickRepository clicks;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * {@code saveIfAbsent} commits in its own transaction by design, so those rows survive the
     * rollback that normally isolates a {@code @DataJpaTest}. The tables are therefore truncated
     * explicitly rather than relying on that rollback.
     */
    @BeforeEach
    void clearCommittedRows() {
        jdbc.execute("DELETE FROM clicks");
        jdbc.execute("DELETE FROM idempotency");
        jdbc.execute("DELETE FROM links");
    }

    private static ShortLink link(String code, Instant expiresAt) {
        return new ShortLink(code, URI.create("https://example.org/" + code), T0, expiresAt, null, "key-1");
    }

    @Test
    void migrations_create_the_schema_and_a_link_round_trips() {
        assertThat(links.saveIfAbsent(link("abc1234", null))).isTrue();

        assertThat(links.findByCode("abc1234")).hasValueSatisfying(found -> {
            assertThat(found.target()).hasToString("https://example.org/abc1234");
            assertThat(found.createdAt()).isEqualTo(T0);
            assertThat(found.expiresAt()).isNull();
            assertThat(found.createdByKeyId()).isEqualTo("key-1");
        });
    }

    @Test
    void an_expiry_survives_the_round_trip_with_instant_precision() {
        Instant expiry = Instant.parse("2026-12-31T23:59:59Z");
        links.saveIfAbsent(link("exp1234", expiry));

        assertThat(links.findByCode("exp1234").orElseThrow().expiresAt()).isEqualTo(expiry);
    }

    @Test
    void saving_an_existing_code_reports_a_collision_instead_of_overwriting() {
        links.saveIfAbsent(link("dup1234", null));

        assertThat(links.saveIfAbsent(link("dup1234", null))).isFalse();
        assertThat(links.count()).isEqualTo(1);
    }

    @Test
    void concurrent_writers_of_the_same_code_produce_exactly_one_winner() throws Exception {
        int writers = 8;
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) {
                pool.submit(() -> {
                    startGun.await();
                    if (links.saveIfAbsent(link("race123", null))) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            startGun.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(winners.get()).isEqualTo(1);
        assertThat(links.count()).isEqualTo(1);
    }

    @Test
    void deleting_reports_whether_anything_was_removed() {
        links.saveIfAbsent(link("del1234", null));

        assertThat(links.deleteByCode("del1234")).isTrue();
        assertThat(links.deleteByCode("del1234")).isFalse();
        assertThat(links.findByCode("del1234")).isEmpty();
    }

    @Test
    void the_idempotency_ledger_round_trips() {
        idempotency.save(new IdempotencyRecord("idem-1", "hash-1", "abc1234", T0));

        assertThat(idempotency.find("idem-1")).hasValueSatisfying(record -> {
            assertThat(record.requestHash()).isEqualTo("hash-1");
            assertThat(record.code()).isEqualTo("abc1234");
        });
        assertThat(idempotency.find("absent")).isEmpty();
    }

    @Test
    void clicks_aggregate_into_daily_buckets_within_the_requested_window() {
        links.saveIfAbsent(link("stat123", null));
        Instant day1 = Instant.parse("2026-09-27T09:00:00Z");
        Instant day2 = Instant.parse("2026-09-28T09:00:00Z");

        clicks.saveAll(List.of(
                new ClickEvent("stat123", day1, "ref.example", ClickEvent.UA_DESKTOP),
                new ClickEvent("stat123", day1.plusSeconds(60), null, ClickEvent.UA_BOT),
                new ClickEvent("stat123", day2, null, ClickEvent.UA_MOBILE)));

        LinkStats stats = clicks.statsFor("stat123",
                Instant.parse("2026-09-27T00:00:00Z"), Instant.parse("2026-09-29T00:00:00Z"));

        assertThat(stats.totalClicks()).isEqualTo(3);
        assertThat(stats.lastClickAt()).isEqualTo(day2);
        assertThat(stats.daily()).containsExactly(
                new LinkStats.DailyClicks(LocalDate.of(2026, 9, 27), 2L),
                new LinkStats.DailyClicks(LocalDate.of(2026, 9, 28), 1L));
    }

    @Test
    void a_window_that_excludes_every_click_reports_zero_without_failing() {
        links.saveIfAbsent(link("empt123", null));
        clicks.saveAll(List.of(new ClickEvent("empt123", T0, null, ClickEvent.UA_UNKNOWN)));

        LinkStats stats = clicks.statsFor("empt123",
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-02T00:00:00Z"));

        assertThat(stats.totalClicks()).isZero();
        assertThat(stats.daily()).isEmpty();
        assertThat(clicks.countFor("empt123")).isEqualTo(1);
    }
}
