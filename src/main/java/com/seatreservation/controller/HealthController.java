package com.seatreservation.controller;

import jakarta.persistence.EntityManager;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final EntityManager entityManager;

    public HealthController(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Liveness: always 200 if the JVM is running.
     */
    @GetMapping("/live")
    public Map<String, Object> live() {
        return Map.of("status", "UP", "timestamp", Instant.now().toString());
    }

    /**
     * Readiness: 200 only if the database is reachable.
     * Fails closed (503) when the DB is down.
     */
    @GetMapping("/ready")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> ready() {
        try {
            entityManager.createNativeQuery("SELECT 1").getSingleResult();
            return ResponseEntity.ok(Map.of(
                "status", "UP",
                "db", "reachable",
                "timestamp", Instant.now().toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "DOWN",
                "db", "unreachable",
                "error", e.getMessage(),
                "timestamp", Instant.now().toString()
            ));
        }
    }
}
