package com.redis.workshop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.config.RedisScanHelper;
import com.redis.workshop.config.RedisStartupHelper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache-Aside Service — demonstrates the cache-aside (lazy-loading) pattern.
 * Banking context: caching banking product catalog data.
 *
 * Key pattern: uc10:product:{productId}
 * TTL: 300 seconds (5 minutes)
 */
@Service
@DependsOn("startupCleanup")
public class CacheAsideService {

    private static final Logger log = LoggerFactory.getLogger(CacheAsideService.class);

    private static final String CACHE_PREFIX = "uc10:product:";
    private static final String MOCK_DB_PREFIX = "uc10:mockdb:";
    private static final long CACHE_TTL_SECONDS = 300;
    private static final long DB_SIMULATED_DELAY_MS = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${workshop.startup.load-data:true}")
    private boolean loadData;

    @Value("${workshop.startup.force-reload:false}")
    private boolean forceReload;

    // Mock "database" — simulates a slow relational DB
    private final Map<String, Map<String, Object>> mockDatabase = new LinkedHashMap<>();

    // Cache statistics
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong totalHitLatencyMs = new AtomicLong();
    private final AtomicLong totalMissLatencyMs = new AtomicLong();

    public CacheAsideService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!loadData) return;
        mockDatabase.clear();

        List<Map<String, Object>> products = List.of(
                buildProduct("mortgage-fixed",
                "Fixed Rate Mortgage", "Mortgage", "3.25%", "25 years",
                "€50,000", "€500,000", "Stable owner-occupier mortgage for predictable monthly payments", "2024-01-15"),
                buildProduct("mortgage-variable",
                "Variable Rate Mortgage", "Mortgage", "Euribor + 1.5%", "30 years",
                "€50,000", "€750,000", "Variable rate mortgage linked to Euribor", "2024-01-15"),
                buildProduct("savings-premium",
                "Premium Savings Account", "Savings", "2.10%", null,
                "€10,000", null, "High-yield savings for premium clients", "2024-02-01"),
                buildProduct("savings-goal",
                "Goal Saver Pocket", "Savings", "1.85%", null,
                "€500", null, "Digital savings pocket for travel, tuition, or emergency goals", "2024-02-14"),
                buildProduct("checking-everyday",
                "Everyday Checking", "Checking", null, null,
                "€0", null, "Simple current account for salary deposits and daily payments", "2024-01-05"),
                buildProduct("checking-premium",
                "Premier Current Account", "Checking", null, null,
                "€5,000", null, "Premium checking with concierge support and travel perks", "2024-03-10"),
                buildProduct("credit-gold",
                "Gold Credit Card", "Credit Card", null, null,
                null, null, "Premium credit card with travel insurance and cashback", "2024-01-20"),
                buildProduct("credit-platinum",
                "Platinum Travel Card", "Credit Card", null, null,
                null, null, "High-limit travel card with lounge access and concierge services", "2024-03-18"),
                buildProduct("business-loan",
                "Business Growth Loan", "Business", "4.50%", "10 years",
                "€25,000", "€1,000,000", "Flexible business loan for growth and expansion", "2024-03-01"),
                buildProduct("business-treasury",
                "Corporate Treasury Line", "Business", "3.95%", "3 years",
                "€100,000", "€2,500,000", "Liquidity line for payroll peaks, FX settlements, and working capital", "2024-04-02"),
                buildProduct("investment-income",
                "Income Portfolio Plus", "Investment", "5.20% target", "Open-ended",
                "€5,000", null, "Diversified income portfolio combining bonds, dividend equities, and short-duration funds", "2024-02-28"),
                buildProduct("investment-esg",
                "ESG Future Portfolio", "Investment", "6.10% target", "Open-ended",
                "€2,500", null, "Sustainability-focused model portfolio for long-term wealth accumulation", "2024-04-08")
        );

        for (Map<String, Object> product : products) {
            mockDatabase.put(String.valueOf(product.get("id")), product);
        }

        if (forceReload) {
            log.info("UC10: force reload enabled for mock DB markers, rebuilding {} products", products.size());
        } else {
            long existingKeys = RedisStartupHelper.countKeys(redis, MOCK_DB_PREFIX + "*");
            if (existingKeys >= products.size()) {
                log.info("UC10: mock DB keys already present ({} keys), skipping reload", existingKeys);
                return;
            }
        }

        for (Map<String, Object> product : products) {
            redis.opsForHash().putAll(MOCK_DB_PREFIX + product.get("id"), stringify(product));
        }
    }

    private Map<String, String> stringify(Map<String, Object> product) {
        Map<String, String> fields = new LinkedHashMap<>();
        product.forEach((key, value) -> fields.put(key, value == null ? "" : value.toString()));
        return fields;
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
        if ("Checking".equals(type)) {
            product.put("monthlyFee", "€0");
            product.put("debitCard", "Included");
            product.put("instantTransfers", "Included");
        }
        if ("Investment".equals(type)) {
            product.put("riskLevel", "Moderate");
            product.put("managementStyle", "Model portfolio");
            product.put("currency", "EUR");
        }
        if ("Mortgage".equals(type)) {
            product.put("maxLtv", "80%");
            product.put("repaymentType", "Monthly amortizing");
        }
        if ("Business".equals(type)) {
            product.put("collateral", "Case-by-case");
            product.put("repaymentFlexibility", "Early repayment allowed");
        }
        if ("credit-platinum".equals(id)) {
            product.put("annualFee", "€160");
            product.put("creditLimit", "€35,000");
            product.put("cashback", "2.0%");
            product.put("airportLoungeVisits", "12 per year");
        }
        if ("checking-premium".equals(id)) {
            product.put("monthlyFee", "€18");
            product.put("advisorTier", "Premium desk");
            product.put("travelInsurance", "Included");
        }
        if ("investment-esg".equals(id)) {
            product.put("riskLevel", "Moderate-High");
            product.put("theme", "Climate transition and clean infrastructure");
        }
        return product;
    }

    public Map<String, Object> getProduct(String productId) {
        long start = System.nanoTime();
        String cacheKey = CACHE_PREFIX + productId;

        // 1. Check cache (Redis GET)
        String cached = redis.opsForValue().get(cacheKey);

        if (cached != null) {
            // CACHE HIT
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            hits.incrementAndGet();
            totalHitLatencyMs.addAndGet(latencyMs);
            Map<String, Object> product = deserialize(cached);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("product", product);
            result.put("cacheHit", true);
            result.put("latencyMs", latencyMs);
            result.put("source", "CACHE");
            return result;
        }

        // 2. CACHE MISS — fetch from "database"
        misses.incrementAndGet();

        // Simulate slow DB query
        try { Thread.sleep(DB_SIMULATED_DELAY_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Map<String, Object> product = mockDatabase.get(productId);
        if (product == null) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Product not found");
            err.put("productId", productId);
            err.put("latencyMs", latencyMs);
            return err;
        }

        // 3. Store in cache with TTL (SET with EX)
        String productJson = serialize(product);
        redis.opsForValue().set(cacheKey, productJson, Duration.ofSeconds(CACHE_TTL_SECONDS));

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        totalMissLatencyMs.addAndGet(latencyMs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("product", product);
        result.put("cacheHit", false);
        result.put("latencyMs", latencyMs);
        result.put("source", "DATABASE");
        return result;
    }

    public Map<String, Object> evictProduct(String productId) {
        String cacheKey = CACHE_PREFIX + productId;
        Boolean deleted = redis.delete(cacheKey);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evicted", Boolean.TRUE.equals(deleted));
        result.put("productId", productId);
        return result;
    }

    public Map<String, Object> evictAll() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, CACHE_PREFIX + "*");
        int count = 0;
        if (!keys.isEmpty()) {
            count = keys.size();
            redis.delete(keys);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evicted", true);
        result.put("count", count);
        return result;
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
