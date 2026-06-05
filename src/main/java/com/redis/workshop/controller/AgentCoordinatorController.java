package com.redis.workshop.controller;

import com.redis.workshop.service.AgentCoordinatorService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/agents")
public class AgentCoordinatorController {

    private final AgentCoordinatorService agentCoordinatorService;

    public AgentCoordinatorController(AgentCoordinatorService agentCoordinatorService) {
        this.agentCoordinatorService = agentCoordinatorService;
    }

    @PostMapping(value = "/coordinate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter coordinate(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> request = body != null ? body : Map.of();
        String query = request.getOrDefault("query", "");
        String userId = request.getOrDefault("userId", "demo-user");

        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                agentCoordinatorService.coordinate(query, userId, emitter);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(agentCoordinatorService.getAgentStatus());
    }

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> events(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(agentCoordinatorService.getRecentEvents(limit));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        return ResponseEntity.ok(agentCoordinatorService.reset());
    }
}