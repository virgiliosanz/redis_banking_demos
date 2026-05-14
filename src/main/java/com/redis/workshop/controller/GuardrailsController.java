package com.redis.workshop.controller;

import com.redis.workshop.service.GuardrailsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/guardrails")
public class GuardrailsController {

    private final GuardrailsService guardrailsService;

    public GuardrailsController(GuardrailsService guardrailsService) {
        this.guardrailsService = guardrailsService;
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        String message = request == null ? "" : String.valueOf(request.getOrDefault("message", ""));
        String userId = request == null ? "demo-user" : String.valueOf(request.getOrDefault("userId", "demo-user"));

        if (message == null || message.isBlank()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "ERROR");
            error.put("message", "Request body must include a non-empty 'message'");
            return ResponseEntity.badRequest().body(error);
        }

        return ResponseEntity.ok(guardrailsService.chat(userId, message));
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(guardrailsService.getAudit(limit));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(guardrailsService.getStats());
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        return ResponseEntity.ok(guardrailsService.reset());
    }
}