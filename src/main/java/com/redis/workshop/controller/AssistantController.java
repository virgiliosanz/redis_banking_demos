package com.redis.workshop.controller;

import com.redis.workshop.service.AssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
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
     * Reset all assistant data.
     * POST /api/assistant/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        assistantService.reset();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Assistant demo reset complete"));
    }
}
