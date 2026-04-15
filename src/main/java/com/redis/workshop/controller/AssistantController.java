package com.redis.workshop.controller;

import com.redis.workshop.service.AssistantService;
import com.redis.workshop.service.OpenAiService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;
    private final OpenAiService openAiService;

    public AssistantController(AssistantService assistantService, OpenAiService openAiService) {
        this.assistantService = assistantService;
        this.openAiService = openAiService;
    }

    /**
     * Send a message to the AI assistant.
     * POST /api/assistant/chat
     * Body: { "sessionId": "...", "userName": "...", "message": "..." }
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String sessionId = request.getOrDefault("sessionId", "");
        String userName = request.getOrDefault("userName", "Demo User");
        String message = request.getOrDefault("message", "");

        if (sessionId.isBlank() || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "sessionId and message are required"
            ));
        }

        Map<String, Object> result = assistantService.chat(sessionId, userName, message);
        return ResponseEntity.ok(result);
    }

    /**
     * Get current conversation (short-term memory inspection).
     * GET /api/assistant/conversation/{sessionId}
     */
    @GetMapping("/conversation/{sessionId}")
    public ResponseEntity<Map<String, Object>> getConversation(@PathVariable String sessionId) {
        Map<String, Object> conversation = assistantService.getConversation(sessionId);
        return ResponseEntity.ok(conversation);
    }

    /**
     * List all long-term memories.
     * GET /api/assistant/memories
     */
    @GetMapping("/memories")
    public ResponseEntity<List<Map<String, String>>> listMemories() {
        return ResponseEntity.ok(assistantService.listMemories());
    }

    /**
     * List all knowledge base articles.
     * GET /api/assistant/kb
     */
    @GetMapping("/kb")
    public ResponseEntity<List<Map<String, String>>> listKB() {
        return ResponseEntity.ok(assistantService.listKBArticles());
    }

    /**
     * Streaming chat via SSE (requires OpenAI API key).
     * GET /api/assistant/chat/stream?sessionId=...&message=...&userName=...
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "Demo User") String userName,
            @RequestParam String message) {

        SseEmitter emitter = new SseEmitter(60000L); // 60s timeout

        CompletableFuture.runAsync(() -> {
            try {
                assistantService.chatStream(sessionId, userName, message, emitter);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Check if OpenAI is configured.
     * GET /api/assistant/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "openaiConfigured", openAiService.isConfigured(),
                "mode", openAiService.isConfigured() ? "openai" : "mock"
        ));
    }

    /**
     * Get semantic cache statistics.
     * GET /api/assistant/cache/stats
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> cacheStats() {
        return ResponseEntity.ok(assistantService.getSemanticCacheStats());
    }

    /**
     * Reset all assistant data.
     * POST /api/assistant/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        assistantService.reset();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Assistant demo reset complete"));
    }
}
