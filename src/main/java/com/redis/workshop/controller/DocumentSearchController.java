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

    /**
     * Query documents by field value.
     * GET /api/docs/query?field=category&value=PSD2
     */
    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> queryByField(
            @RequestParam String field,
            @RequestParam String value) {

        if (field == null || field.isBlank() || value == null || value.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "Both 'field' and 'value' parameters are required"
            ));
        }

        return ResponseEntity.ok(documentSearchService.queryByField(field, value));
    }

    /**
     * Read a single document by ID.
     * GET /api/docs/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable String id) {
        Map<String, Object> result = documentSearchService.getById(id);
        if ("NOT_FOUND".equals(result.get("status"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Read a specific field from a document.
     * GET /api/docs/{id}/{path}
     */
    @GetMapping("/{id}/{path}")
    public ResponseEntity<Map<String, Object>> getField(
            @PathVariable String id,
            @PathVariable String path) {
        Map<String, Object> result = documentSearchService.getField(id, path);
        if ("NOT_FOUND".equals(result.get("status"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Create a new custom document.
     * POST /api/docs
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDocument(@RequestBody Map<String, Object> doc) {
        Map<String, Object> result = documentSearchService.createDocument(doc);
        return ResponseEntity.status(201).body(result);
    }
}
