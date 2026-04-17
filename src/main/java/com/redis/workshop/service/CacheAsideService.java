package com.redis.workshop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.config.RedisCommandLogger;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache-Aside Service — demonstrates the cache-aside (lazy-loading) pattern.
 * Banking context: caching banking product catalog data.
 *
 * Key pattern: workshop:cache:product:{productId}
 * TTL: 300 seconds (5 minutes)
 */
@Service
public class CacheAsideService {

    private static final String CACHE_PREFIX = "workshop:cache:product:";
    private static final long CACHE_TTL_SECONDS = 300;
    private static final long DB_SIMULATED_DELAY_MS = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisCommandLogger commandLogger;

    // Mock "database" — simulates a slow relational DB
    private final Map<String, Map<String, Object>> mockDatabase = new LinkedHashMap<>();

    // Cache statistics
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong totalHitLatencyMs = new AtomicLong();
    private final AtomicLong totalMissLatencyMs = new AtomicLong();

    public CacheAsideService(StringRedisTemplate redis, ObjectMapper objectMapper,
                              RedisCommandLogger commandLogger) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.commandLogger = commandLogger;
    }

    @PostConstruct
    public void init() {
        mockDatabase.put("mortgage-fixed", buildProduct("mortgage-fixed",
                "Fixed Rate Mortgage", "Mortgage", "3.25%", "25 years",
                "€50,000", "€500,000", "Fixed rate mortgage with stable monthly payments", "2024-01-15"));
        mockDatabase.put("mortgage-variable", buildProduct("mortgage-variable",
                "Variable Rate Mortgage", "Mortgage", "Euribor + 1.5%", "30 years",
                "€50,000", "€750,000", "Variable rate mortgage linked to Euribor", "2024-01-15"));
        mockDatabase.put("savings-premium", buildProduct("savings-premium",
                "Premium Savings Account", "Savings", "2.10%", null,
                "€10,000", null, "High-yield savings for premium clients", "2024-02-01"));
        mockDatabase.put("credit-gold", buildProduct("credit-gold",
                "Gold Credit Card", "Credit Card", null, null,
                null, null, "Premium credit card with travel insurance and cashback", "2024-01-20"));
        mockDatabase.put("business-loan", buildProduct("business-loan",
                "Business Growth Loan", "Business", "4.50%", "10 years",
                "€25,000", "€1,000,000", "Flexible business loan for growth and expansion", "2024-03-01"));
    }

    private Map<String, Object> buildProduct(String id, String name, String type, String rate,
                                              String term, String minAmount, String maxAmount,
                                              String description, String lastUpdated) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", id);
        product.put("name", name);
        product.put("type", type);
        if (rate != null) product.put("interestRate", rate);
        if (term != null) product.put("term", term);
        if (minAmount != null) product.put("minAmount", minAmount);
        if (maxAmount != null) product.put("maxAmount", maxAmount);
        product.put("description", description);
        product.put("lastUpdated", lastUpdated);
        // Extra fields for specific product types
        if ("Credit Card".equals(type)) {
            product.put("annualFee", "€75");
            product.put("creditLimit", "€15,000");
            product.put("cashback", "1.5%");
        }
        if ("Savings".equals(type)) {
            product.put("minBalance", "€10,000");
            product.put("features", "No maintenance fees, online banking");
        }
        return product;
    }

    public Map<String, Object> getProduct(String productId) {
        long start = System.nanoTime();
        String cacheKey = CACHE_PREFIX + productId;

        // 1. Check cache (Redis GET)
        String cached = redis.opsForValue().get(cacheKey);
        commandLogger.log("UC10", "GET", cacheKey, cached != null ? "HIT" : "MISS");

        if (cached != null) {
            // CACHE HIT
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            hits.incrementAndGet();
            totalHitLatencyMs.addAndGet(latencyMs);
            Map<String, Object> product = deserialize(cached);
            return Map.of("product", product, "cacheHit", true, "latencyMs", latencyMs, "source", "CACHE");
        }

        // 2. CACHE MISS — fetch from "database"
        misses.incrementAndGet();

        // Simulate slow DB query
        try { Thread.sleep(DB_SIMULATED_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Map<String, Object> product = mockDatabase.get(productId);
        if (product == null) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return Map.of("error", "Product not found", "productId", productId, "latencyMs", latencyMs);
        }

        // 3. Store in cache with TTL (SET with EX)
        redis.opsForValue().set(cacheKey, serialize(product), Duration.ofSeconds(CACHE_TTL_SECONDS));
        commandLogger.log("UC10", "SET EX", cacheKey, CACHE_TTL_SECONDS + "s");

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        totalMissLatencyMs.addAndGet(latencyMs);

        return Map.of("product", product, "cacheHit", false, "latencyMs", latencyMs, "source", "DATABASE");
    }

    public Map<String, Object> evictProduct(String productId) {
        String cacheKey = CACHE_PREFIX + productId;
        Boolean deleted = redis.delete(cacheKey);
        commandLogger.log("UC10", "DEL", cacheKey);
        return Map.of("evicted", Boolean.TRUE.equals(deleted), "productId", productId);
    }

    public Map<String, Object> evictAll() {
        Set<String> keys = redis.keys(CACHE_PREFIX + "*");
        int count = 0;
        if (keys != null && !keys.isEmpty()) {
            count = keys.size();
            redis.delete(keys);
        }
        return Map.of("evicted", true, "count", count);
    }

    public Map<String, Object> getStats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        String hitRatio = total > 0 ? String.format("%.0f%%", (h * 100.0) / total) : "N/A";
        long avgHit = h > 0 ? totalHitLatencyMs.get() / h : 0;
        long avgMiss = m > 0 ? totalMissLatencyMs.get() / m : 0;
        return Map.of("hits", h, "misses", m, "totalRequests", total,
                "hitRatio", hitRatio, "avgHitLatencyMs", avgHit, "avgMissLatencyMs", avgMiss);
    }

    public List<Map<String, Object>> listProducts() {
        return List.copyOf(mockDatabase.values());
    }

    private String serialize(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map); } catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    private Map<String, Object> deserialize(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); } catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }
}
