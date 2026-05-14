package com.redis.workshop.controller;

import com.redis.workshop.service.AiGatewayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/gateway")
public class AiGatewayController {

    private final AiGatewayService aiGatewayService;

    public AiGatewayController(AiGatewayService aiGatewayService) {
        this.aiGatewayService = aiGatewayService;
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, String> request) {
        String query = request.getOrDefault("query", "").trim();
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }

        String userId = request.getOrDefault("userId", "demo-user").trim();
        String sessionId = request.getOrDefault("sessionId", userId.isBlank() ? "demo-session" : userId).trim();

        Map<String, Object> result = aiGatewayService.handleQuery(query,
                userId.isBlank() ? "demo-user" : userId,
                sessionId.isBlank() ? "demo-session" : sessionId);

        boolean rateLimited = Boolean.TRUE.equals(result.get("rateLimited"));
        return ResponseEntity.status(rateLimited ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.OK).body(result);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(aiGatewayService.getStats());
    }

    @GetMapping("/log")
    public ResponseEntity<Map<String, Object>> log(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(aiGatewayService.getRequestLog(Math.max(1, Math.min(limit, 100))));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        aiGatewayService.reset();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "AI Gateway demo reset complete"));
    }
}