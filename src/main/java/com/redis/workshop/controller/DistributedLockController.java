package com.redis.workshop.controller;

import com.redis.workshop.service.DistributedLockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for UC13: Distributed Locking.
 * Demonstrates lock acquisition with SET NX EX, safe release with Lua script,
 * and contention simulation for banking account transfers.
 */
@RestController
@RequestMapping("/api/lock")
public class DistributedLockController {

    private final DistributedLockService lockService;

    public DistributedLockController(DistributedLockService lockService) {
        this.lockService = lockService;
    }

    /**
     * Acquire a distributed lock.
     * POST /api/lock/acquire
     * Body: { "resourceId": "acc-001", "clientId": "client-A", "ttlSeconds": 30 }
     */
    @PostMapping("/acquire")
    public ResponseEntity<Map<String, Object>> acquireLock(@RequestBody Map<String, Object> body) {
        String resourceId = (String) body.get("resourceId");
        String clientId = (String) body.get("clientId");
        int ttlSeconds = body.containsKey("ttlSeconds")
                ? ((Number) body.get("ttlSeconds")).intValue()
                : 30;

        if (resourceId == null || clientId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "resourceId and clientId are required"));
        }

        Map<String, Object> result = lockService.acquireLock(resourceId, clientId, ttlSeconds);
        return ResponseEntity.ok(result);
    }

    /**
     * Release a distributed lock (safe release via Lua script).
     * POST /api/lock/release
     * Body: { "resourceId": "acc-001", "clientId": "client-A" }
     */
    @PostMapping("/release")
    public ResponseEntity<Map<String, Object>> releaseLock(@RequestBody Map<String, Object> body) {
        String resourceId = (String) body.get("resourceId");
        String clientId = (String) body.get("clientId");

        if (resourceId == null || clientId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "resourceId and clientId are required"));
        }

        Map<String, Object> result = lockService.releaseLock(resourceId, clientId);
        return ResponseEntity.ok(result);
    }

    /**
     * Get lock info: current holder and remaining TTL.
     * GET /api/lock/info/{resourceId}
     */
    @GetMapping("/info/{resourceId}")
    public ResponseEntity<Map<String, Object>> getLockInfo(@PathVariable String resourceId) {
        Map<String, Object> result = lockService.getLockInfo(resourceId);
        return ResponseEntity.ok(result);
    }

    /**
     * Simulate contention: 3 concurrent clients try to acquire the same lock.
     * POST /api/lock/simulate
     * Body: { "resourceId": "acc-002" }
     */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulateContention(@RequestBody Map<String, Object> body) {
        String resourceId = (String) body.get("resourceId");
        if (resourceId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "resourceId is required"));
        }

        Map<String, Object> result = lockService.simulateContention(resourceId);
        return ResponseEntity.ok(result);
    }
}
