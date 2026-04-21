package com.redis.workshop.controller;

import com.redis.workshop.service.AmsDemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for the new Agent Memory Server use case. Deliberately separate
 * from {@link AssistantController} so UC9 remains intact and the two cases can
 * be demoed side-by-side. All responses surface the AMS requests/responses via
 * {@code /api/ams/traces} so the UI can render a working memory vs long-term
 * memory observability panel without scraping Redis directly.
 */
@RestController
@RequestMapping("/api/ams")
public class AmsController {

    private final AmsDemoService ams;

    public AmsController(AmsDemoService ams) {
        this.ams = ams;
    }

    /** Best-effort status + reachability probe. Always returns 200. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(ams.status());
    }

    /** Seed curated banking long-term memories and initialize empty working memory. */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> req = body != null ? body : Map.of();
        String sessionId = req.getOrDefault("sessionId", "demo-session");
        String userId = req.getOrDefault("userId", "demo-user");
        return ResponseEntity.ok(ams.seed(sessionId, userId));
    }

    /** Delete working memory for the session and remove seeded long-term memories. */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> req = body != null ? body : Map.of();
        String sessionId = req.getOrDefault("sessionId", "demo-session");
        String userId = req.getOrDefault("userId", "demo-user");
        return ResponseEntity.ok(ams.reset(sessionId, userId));
    }

    /**
     * One conversation turn via AMS: appends to working memory, assembles
     * context via {@code /v1/memory/prompt}, returns the context + LLM reply.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String sessionId = request.getOrDefault("sessionId", "");
        String userId = request.getOrDefault("userId", "demo-user");
        String message = request.getOrDefault("message", "");
        if (sessionId.isBlank() || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "sessionId and message are required"));
        }
        return ResponseEntity.ok(ams.chat(sessionId, userId, message));
    }

    @GetMapping("/working-memory/{sessionId}")
    public ResponseEntity<Map<String, Object>> getWorkingMemory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "demo-user") String userId) {
        return ResponseEntity.ok(ams.getWorkingMemory(sessionId, userId));
    }

    /** Standalone long-term memory search (semantic by default, filtered by user). */
    @PostMapping("/memories/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, Object> request) {
        String query = request.getOrDefault("query", "").toString();
        String userId = request.getOrDefault("userId", "demo-user").toString();
        Integer limit = request.get("limit") instanceof Number n ? n.intValue() : null;
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }
        return ResponseEntity.ok(ams.searchMemories(query, userId, limit));
    }

    /**
     * Direct context assembly observability: runs {@code /v1/memory/prompt}
     * without producing a chat turn, so the UI can visualize what AMS feeds
     * to the LLM for a given query.
     */
    @PostMapping("/memory-prompt")
    public ResponseEntity<Map<String, Object>> memoryPrompt(@RequestBody Map<String, String> request) {
        String query = request.getOrDefault("query", "");
        String sessionId = request.getOrDefault("sessionId", "");
        String userId = request.getOrDefault("userId", "demo-user");
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }
        return ResponseEntity.ok(ams.memoryPrompt(query, sessionId, userId));
    }

    /** Recent AMS request/response traces captured by {@code AmsTraceRecorder}. */
    @GetMapping("/traces")
    public ResponseEntity<Map<String, Object>> traces(@RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> entries = ams.recentTraces(limit);
        return ResponseEntity.ok(Map.of(
                "count", entries.size(),
                "traces", entries));
    }
}
