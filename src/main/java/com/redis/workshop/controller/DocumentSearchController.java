package com.redis.workshop.controller;

import com.redis.workshop.service.DocumentSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
public class DocumentSearchController {

    private final DocumentSearchService documentSearchService;

    public DocumentSearchController(DocumentSearchService documentSearchService) {
        this.documentSearchService = documentSearchService;
    }

    /**
     * Search documents.
     * GET /api/docs/search?q=payment&mode=full-text|vector|hybrid
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "full-text") String mode) {

        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "Query parameter 'q' is required"
            ));
        }

        Map<String, Object> result = switch (mode) {
            case "vector" -> documentSearchService.vectorSearch(q);
            case "hybrid" -> documentSearchService.hybridSearch(q);
            default -> documentSearchService.fullTextSearch(q);
        };

        return ResponseEntity.ok(result);
    }

    /**
     * List all regulation documents.
     * GET /api/docs/list
     */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> listDocuments() {
        return ResponseEntity.ok(documentSearchService.listDocuments());
    }
}
