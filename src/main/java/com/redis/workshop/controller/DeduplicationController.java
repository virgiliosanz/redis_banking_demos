package com.redis.workshop.controller;

import com.redis.workshop.service.DeduplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dedup")
public class DeduplicationController {

    private final DeduplicationService deduplicationService;

    public DeduplicationController(DeduplicationService deduplicationService) {
        this.deduplicationService = deduplicationService;
    }

    /**
     * Submit a transaction for deduplication check.
     * POST /api/dedup/submit
     * Body: { "sender": "...", "receiver": "...", "amount": "..." }
     */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, String> request) {
        String sender = request.getOrDefault("sender", "");
        String receiver = request.getOrDefault("receiver", "");
        String amount = request.getOrDefault("amount", "");

        if (sender.isBlank() || receiver.isBlank() || amount.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "sender, receiver, and amount are required"
            ));
        }

        Map<String, Object> result = deduplicationService.submitTransaction(sender, receiver, amount);
        return ResponseEntity.ok(result);
    }

    /**
     * Get the transaction log.
     * GET /api/dedup/log
     */
    @GetMapping("/log")
    public ResponseEntity<List<Map<String, Object>>> getLog() {
        return ResponseEntity.ok(deduplicationService.getTransactionLog());
    }

    /**
     * Clear the transaction log (demo reset).
     * POST /api/dedup/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        deduplicationService.clearLog();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Log cleared"));
    }
}
