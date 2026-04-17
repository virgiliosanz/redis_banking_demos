package com.redis.workshop.controller;

import com.redis.workshop.service.TransactionMonitorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionMonitorController {

    private final TransactionMonitorService monitorService;

    public TransactionMonitorController(TransactionMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /**
     * Start transaction simulation — generates random transactions every 500ms.
     */
    @PostMapping("/simulate/start")
    public ResponseEntity<Map<String, Object>> startSimulation() {
        monitorService.startSimulation();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Simulation started", "simulating", true));
    }

    /**
     * Stop transaction simulation.
     */
    @PostMapping("/simulate/stop")
    public ResponseEntity<Map<String, Object>> stopSimulation() {
        monitorService.stopSimulation();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Simulation stopped", "simulating", false));
    }

    /**
     * Inject an anomaly burst — 20 high-value, high-risk transactions.
     */
    @PostMapping("/simulate/anomaly")
    public ResponseEntity<Map<String, Object>> injectAnomaly() {
        monitorService.injectAnomaly();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Anomaly injected: 20 high-risk transactions"));
    }

    /**
     * Get current metrics for the dashboard.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(monitorService.getMetrics());
    }

    /**
     * Get time series history for a specific range with aggregation.
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "0") long from,
            @RequestParam(defaultValue = "0") long to,
            @RequestParam(defaultValue = "SUM") String aggregation,
            @RequestParam(defaultValue = "1000") long bucketDurationMs) {
        if (to == 0) to = System.currentTimeMillis();
        if (from == 0) from = to - 60_000;
        return ResponseEntity.ok(monitorService.getHistory(from, to, aggregation, bucketDurationMs));
    }

    /**
     * Reset all time series data.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        monitorService.reset();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Transaction monitoring data reset"));
    }
}
