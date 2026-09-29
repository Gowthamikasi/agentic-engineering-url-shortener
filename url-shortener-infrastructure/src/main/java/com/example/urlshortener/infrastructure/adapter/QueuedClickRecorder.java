package com.example.urlshortener.infrastructure.adapter;

import com.example.urlshortener.domain.model.ClickEvent;
import com.example.urlshortener.domain.port.ClickRecorder;
import com.example.urlshortener.domain.port.ClickRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records clicks off the redirect path.
 *
 * <p>The redirect handler offers an event to a bounded queue and returns; one consumer
 * thread drains it in batches. So counts are eventually consistent, and under a flood the
 * queue drops events rather than making redirects wait.
 */
@Component
public class QueuedClickRecorder implements ClickRecorder, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueuedClickRecorder.class);

    private final ClickRepository repository;
    private final LinkedBlockingQueue<ClickEvent> queue;
    private final int batchSize;
    private final long drainIntervalMs;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong persisted = new AtomicLong();

    private volatile boolean running;
    private Thread consumer;
    private CountDownLatch stopped;

    public QueuedClickRecorder(ClickRepository repository,
                               @Value("${urlshortener.analytics.queue-capacity:10000}") int capacity,
                               @Value("${urlshortener.analytics.batch-size:200}") int batchSize,
                               @Value("${urlshortener.analytics.drain-interval-ms:250}") long drainIntervalMs) {
        this.repository = repository;
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.batchSize = batchSize;
        this.drainIntervalMs = drainIntervalMs;
    }

    @Override
    public boolean record(ClickEvent event) {
        boolean queued = queue.offer(event);
        if (queued) {
            accepted.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
        return queued;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        stopped = new CountDownLatch(1);
        consumer = Thread.ofVirtual().name("click-writer").start(this::drainLoop);
        log.info("Click writer started with capacity {} and batch size {}", queue.remainingCapacity(), batchSize);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (consumer != null) {
            consumer.interrupt();
        }
        try {
            if (stopped != null && !stopped.await(5, TimeUnit.SECONDS)) {
                log.warn("Click writer did not stop within 5s; {} event(s) may be unwritten", queue.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushRemaining();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void drainLoop() {
        try {
            while (running) {
                ClickEvent first = queue.poll(drainIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<ClickEvent> batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                writeBatch(batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stopped.countDown();
        }
    }

    private void flushRemaining() {
        List<ClickEvent> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            writeBatch(remaining);
        }
    }

    private void writeBatch(List<ClickEvent> batch) {
        try {
            repository.saveAll(batch);
            persisted.addAndGet(batch.size());
        } catch (RuntimeException e) {
            // Drop a failed batch rather than retrying forever; the loss is counted and logged.
            dropped.addAndGet(batch.size());
            log.warn("Dropped {} click event(s): {}", batch.size(), e.toString());
        }
    }

    // ------------------------------------------------------------------ observability

    /** Blocks until the queue is empty or the timeout passes. Test and demo helper. */
    public boolean awaitDrained(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.isEmpty();
    }

    public long accepted() {
        return accepted.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public long persisted() {
        return persisted.get();
    }

    public int queueDepth() {
        return queue.size();
    }

    /** A full queue means clicks are being dropped. */
    public boolean isSaturated() {
        return queue.remainingCapacity() == 0;
    }
}
