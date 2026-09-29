package com.example.urlshortener.api.web;

import com.example.urlshortener.infrastructure.adapter.QueuedClickRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Liveness and readiness. */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final DataSource dataSource;
    private final QueuedClickRecorder clickRecorder;

    public HealthController(DataSource dataSource, QueuedClickRecorder clickRecorder) {
        this.dataSource = dataSource;
        this.clickRecorder = clickRecorder;
    }

    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> live() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean databaseUp = isDatabaseReachable();
        boolean queueHealthy = !clickRecorder.isSaturated();

        body.put("status", databaseUp && queueHealthy ? "UP" : "DOWN");
        body.put("database", databaseUp ? "UP" : "DOWN");
        body.put("analyticsQueue", queueHealthy ? "UP" : "SATURATED");
        body.put("analyticsQueueDepth", clickRecorder.queueDepth());
        body.put("analyticsDropped", clickRecorder.dropped());

        HttpStatus status = databaseUp && queueHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
