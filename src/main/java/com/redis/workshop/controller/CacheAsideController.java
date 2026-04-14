package com.redis.workshop.controller;

import com.redis.workshop.service.CacheAsideService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for UC10: Cache-Aside Pattern.
 * Demonstrates caching banking product data with Redis.
 */
@RestController
@RequestMapping("/api/cache")
public class CacheAsideController {

    private final CacheAsideService cacheAsideService;

    public CacheAsideController(CacheAsideService cacheAsideService) {
        this.cacheAsideService = cacheAsideService;
    }

    /**
     * Fetch a banking product — first call = cache miss (slow), second = cache hit (fast).
     */
    @GetMapping("/product/{productId}")
    public ResponseEntity<Map<String, Object>> getProduct(@PathVariable String productId) {
        Map<String, Object> result = cacheAsideService.getProduct(productId);
        if (result.containsKey("error")) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Evict a specific product from cache.
     */
    @DeleteMapping("/product/{productId}")
    public ResponseEntity<Map<String, Object>> evictProduct(@PathVariable String productId) {
        return ResponseEntity.ok(cacheAsideService.evictProduct(productId));
    }

    /**
     * Evict all products from cache.
     */
    @DeleteMapping("/products")
    public ResponseEntity<Map<String, Object>> evictAll() {
        return ResponseEntity.ok(cacheAsideService.evictAll());
    }

    /**
     * Get cache statistics: hits, misses, hit ratio, avg latencies.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(cacheAsideService.getStats());
    }

    /**
     * List all available products in the mock database.
     */
    @GetMapping("/products")
    public ResponseEntity<List<Map<String, Object>>> listProducts() {
        return ResponseEntity.ok(cacheAsideService.listProducts());
    }
}
