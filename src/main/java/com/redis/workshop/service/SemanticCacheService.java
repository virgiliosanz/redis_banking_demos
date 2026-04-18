package com.redis.workshop.service;

import com.redis.workshop.config.RedisScanHelper;
import com.redis.workshop.config.RedisSearchHelper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Semantic cache for UC9: stores question/response pairs with OpenAI embeddings and
 * returns cached responses when a new question is close enough (cosine distance).
 */
@Service
@DependsOn("startupCleanup")
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private static final String CACHE_PREFIX = "uc9:cache:";
    private static final String CACHE_INDEX = "idx:uc9:cache";
    private static final int VECTOR_DIM = 1536;
    private static final long CACHE_TTL_SECONDS = 600;
    private static final double CACHE_DISTANCE_THRESHOLD = 0.15;

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong tokensUsed = new AtomicLong(0);
    private final AtomicLong tokensSaved = new AtomicLong(0);

    private final StringRedisTemplate redis;
    private final OpenAiService openAiService;
    private final RedisSearchHelper redisSearchHelper;

    public SemanticCacheService(StringRedisTemplate redis, OpenAiService openAiService,
                                RedisSearchHelper redisSearchHelper) {
        this.redis = redis;
        this.openAiService = openAiService;
        this.redisSearchHelper = redisSearchHelper;
    }

    @PostConstruct
    public void init() {
        createIndex();
    }

    private void createIndex() {
        RedisVectorOps.dropIndex(redis, CACHE_INDEX);
        RedisVectorOps.createVectorIndex(redis, CACHE_INDEX, CACHE_PREFIX,
                "question TEXT", VECTOR_DIM);
    }

    /**
     * Check semantic cache: KNN search for similar question.
     * Returns cached response map if distance < threshold, null otherwise.
     */
    public Map<String, String> checkSemanticCache(String question) {
        if (!openAiService.isConfigured()) return null;
        try {
            float[] queryVector = openAiService.getEmbedding(question);
            byte[] vectorBytes = RedisSearchHelper.vectorToBytes(queryVector);

            String knnQuery = "*=>[KNN 1 @vector $BLOB]";
            byte[][] binaryArgs = new byte[][] {
                    knnQuery.getBytes(),
                    "PARAMS".getBytes(),
                    "2".getBytes(),
                    "BLOB".getBytes(),
                    vectorBytes,
                    "DIALECT".getBytes(),
                    "2".getBytes()
            };

            List<Object> rawResult = redisSearchHelper.ftSearchWithBinaryArgs(CACHE_INDEX, binaryArgs);
            List<Map<String, String>> parsed = redisSearchHelper.parseSearchResults(rawResult);

            if (!parsed.isEmpty()) {
                Map<String, String> topResult = parsed.get(0);
                String scoreStr = topResult.getOrDefault("__vector_score", "1.0");
                double distance = Double.parseDouble(scoreStr);
                log.debug("UC9 Cache: top result distance={} threshold={}", distance, CACHE_DISTANCE_THRESHOLD);
                if (distance < CACHE_DISTANCE_THRESHOLD) {
                    return topResult;
                }
            }
        } catch (Exception e) {
            log.warn("UC9: Semantic cache lookup failed: {}", e.getMessage());
        }
        return null;
    }

    /** Store question + response in semantic cache with vector and TTL. */
    public void storeInSemanticCache(String question, String response) {
        if (!openAiService.isConfigured()) return;
        try {
            String cacheId = "cache-" + UUID.randomUUID().toString().substring(0, 8);
            String key = CACHE_PREFIX + cacheId;

            float[] embedding = openAiService.getEmbedding(question);

            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("question", question);
            hash.put("response", response);
            redis.opsForHash().putAll(key, hash);
            RedisVectorOps.storeVectorField(redis, key, embedding);
            redis.expire(key, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("UC9: Failed to store in semantic cache: {}", e.getMessage());
        }
    }

    public Map<String, Object> getSemanticCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", openAiService.isConfigured());
        stats.put("hits", cacheHits.get());
        stats.put("misses", cacheMisses.get());
        stats.put("distanceThreshold", CACHE_DISTANCE_THRESHOLD);
        stats.put("ttlSeconds", CACHE_TTL_SECONDS);

        Set<String> cacheKeys = RedisScanHelper.scanKeys(redis, CACHE_PREFIX + "*");
        stats.put("cachedEntries", cacheKeys.size());

        long total = cacheHits.get() + cacheMisses.get();
        stats.put("hitRate", total > 0 ? String.format("%.1f%%", (cacheHits.get() * 100.0) / total) : "N/A");
        stats.put("tokensUsed", tokensUsed.get());
        stats.put("tokensSaved", tokensSaved.get());
        // Estimated cost savings (GPT-4o pricing: ~$5/1M input, ~$15/1M output — simplified to ~$10/1M average)
        double costSavedUsd = (tokensSaved.get() / 1_000_000.0) * 10.0;
        stats.put("estimatedCostSavedUsd", String.format("$%.4f", costSavedUsd));
        return stats;
    }

    // ── Stats mutators (called by AssistantService orchestrator) ────────
    public void recordHit() { cacheHits.incrementAndGet(); }
    public void recordMiss() { cacheMisses.incrementAndGet(); }
    public void addTokensUsed(long n) { tokensUsed.addAndGet(n); }
    public void addTokensSaved(long n) { tokensSaved.addAndGet(n); }

    public void reset() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, CACHE_PREFIX + "*");
        if (!keys.isEmpty()) redis.delete(keys);
        cacheHits.set(0);
        cacheMisses.set(0);
        tokensUsed.set(0);
        tokensSaved.set(0);
        init();
    }
}
