package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.FailureClass;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Agents that drive the engine into a particular success, failure or delay. */
final class TestAgents {

    private TestAgents() {
    }

    /** Succeeds immediately and records that it ran. */
    static final class RecordingAgent implements StageAgent {

        private final String type;
        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();

        RecordingAgent(String type) {
            this.type = type;
        }

        @Override
        public String agentType() {
            return type;
        }

        @Override
        public StageResult execute(StageContext context) {
            invocations.computeIfAbsent(context.nodeId(), k -> new AtomicInteger()).incrementAndGet();
            return StageResult.success("ok: " + context.nodeId())
                    .artifact("Result", "{\"node\":\"" + context.nodeId() + "\"}", List.of())
                    .fact(context.nodeId() + ".ran", true)
                    .build();
        }

        int invocationsFor(String nodeId) {
            AtomicInteger counter = invocations.get(nodeId);
            return counter == null ? 0 : counter.get();
        }
    }

    /** Fails the first {@code failTimes} attempts of each node, then succeeds. */
    static final class FlakyAgent implements StageAgent {

        private final String type;
        private final int failTimes;
        private final FailureClass failureClass;

        FlakyAgent(String type, int failTimes, FailureClass failureClass) {
            this.type = type;
            this.failTimes = failTimes;
            this.failureClass = failureClass;
        }

        @Override
        public String agentType() {
            return type;
        }

        @Override
        public StageResult execute(StageContext context) {
            if (context.attempt() <= failTimes) {
                return StageResult.failure(failureClass,
                        "connection reset on attempt " + context.attempt()).build();
            }
            return StageResult.success("recovered on attempt " + context.attempt()).build();
        }
    }

    /** Always fails, with a chosen classification. */
    static final class FailingAgent implements StageAgent {

        private final String type;
        private final FailureClass failureClass;

        FailingAgent(String type, FailureClass failureClass) {
            this.type = type;
            this.failureClass = failureClass;
        }

        @Override
        public String agentType() {
            return type;
        }

        @Override
        public StageResult execute(StageContext context) {
            return StageResult.failure(failureClass, "deliberate failure in " + context.nodeId()).build();
        }
    }

    /** Sleeps past the node timeout, so the timeout path runs for real. */
    static final class SlowAgent implements StageAgent {

        private final String type;
        private final long sleepMs;

        SlowAgent(String type, long sleepMs) {
            this.type = type;
            this.sleepMs = sleepMs;
        }

        @Override
        public String agentType() {
            return type;
        }

        @Override
        public StageResult execute(StageContext context) throws InterruptedException {
            Thread.sleep(sleepMs);
            return StageResult.success("finished late").build();
        }
    }

    /** Records when each node started and stopped, so overlap can be asserted. */
    static final class OverlapProbeAgent implements StageAgent {

        record Window(String nodeId, Instant start, Instant end) {
        }

        private final String type;
        private final long workMs;
        private final List<Window> windows = new CopyOnWriteArrayList<>();

        OverlapProbeAgent(String type, long workMs) {
            this.type = type;
            this.workMs = workMs;
        }

        @Override
        public String agentType() {
            return type;
        }

        @Override
        public StageResult execute(StageContext context) throws InterruptedException {
            Instant start = Instant.now();
            Thread.sleep(workMs);
            windows.add(new Window(context.nodeId(), start, Instant.now()));
            return StageResult.success("done").build();
        }

        List<Window> windows() {
            return List.copyOf(windows);
        }

        Window windowFor(String nodeId) {
            return windows.stream().filter(w -> w.nodeId().equals(nodeId)).findFirst().orElseThrow();
        }
    }
}
