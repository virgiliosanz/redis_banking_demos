package com.redis.workshop.controller;

import com.redis.workshop.service.RateLimitService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for UC4: Rate Limiting (Open Banking / PSD2).
 * Exposes endpoints to simulate API calls against a Redis-based rate limiter.
 */
@RestController
@RequestMapping("/api/ratelimit")
public class RateLimitController {

    private static final String DEFAULT_CLIENT = "fintech-app-001";

    private final RateLimitService rateLimitService;

    public RateLimitController(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    /**
     * Simulate an API call that is rate-limited.
     * Returns 200 if allowed, 429 if rate limit exceeded.
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkRateLimit(
            @RequestParam(defaultValue = DEFAULT_CLIENT) String clientId) {

        Map<String, Object> result = rateLimitService.checkRateLimit(clientId);
        boolean allowed = (boolean) result.get("allowed");

        HttpStatus status = allowed ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS;
        return ResponseEntity.status(status).body(result);
    }

    /**
     * Get current rate limit status without consuming a request.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestParam(defaultValue = DEFAULT_CLIENT) String clientId) {

        Map<String, Object> status = rateLimitService.getStatus(clientId);
        return ResponseEntity.ok(status);
    }

    /**
     * Reset rate limit for demo purposes.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(
            @RequestParam(defaultValue = DEFAULT_CLIENT) String clientId) {

        Map<String, Object> result = rateLimitService.reset(clientId);
        result.put("status", "reset");
        return ResponseEntity.ok(result);
    }
}
