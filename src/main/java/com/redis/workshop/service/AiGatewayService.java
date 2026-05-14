package com.redis.workshop.service;

import com.redis.workshop.config.RedisScanHelper;
import com.redis.workshop.config.RedisSearchHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AiGatewayService {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);

    private static final String ROUTE_PREFIX = "uc16:route:model:";
    private static final String ROUTE_INDEX = "idx:uc16:routes";
    private static final String CACHE_PREFIX = "uc16:cache:";
    private static final String CACHE_INDEX = "idx:uc16:cache";
    private static final String RATE_LIMIT_PREFIX = "uc16:ratelimit:";
    private static final String USAGE_PREFIX = "uc16:usage:session:";
    private static final String STATS_PREFIX = "uc16:stats:model:";
    private static final String STREAM_KEY = "uc16:stream:gateway";
    private static final int VECTOR_DIM = 10;
    private static final double CACHE_DISTANCE_THRESHOLD = 0.12d;
    private static final long MAX_STREAM_LEN = 500L;

    private static final List<ModelConfig> MODEL_CONFIGS = List.of(
            new ModelConfig(
                    "gpt-4o",
                    "gpt4o",
                    "GPT-4o",
                    "Complex reasoning, regulation analysis, architecture trade-offs",
                    "Explain complex regulations, compare policy obligations, reason through multi-step banking questions, analyze Basel, MiFID, PSD2 and GDPR trade-offs.",
                    "Policy-heavy or multi-step question detected",
                    5,
                    180,
                    180,
                    0.0050,
                    0.0150
            ),
            new ModelConfig(
                    "gpt-4o-mini",
                    "gpt4omini",
                    "GPT-4o-mini",
                    "Fast FAQ, short support answers, product Q&A",
                    "Answer simple factual banking questions, quick FAQs, definitions, product information, short user-facing support prompts and concise summaries.",
                    "Short FAQ-style request detected",
                    20,
                    420,
                    70,
                    0.0003,
                    0.0006
            ),
            new ModelConfig(
                    "internal-numeric",
                    "internalnumeric",
                    "Internal Numeric Model",
                    "Numeric, tabular, metric-heavy and KPI queries",
                    "Handle numeric banking questions, tabular analysis, ratios, limits, quarterly metrics, calculations, percentages, CSV-style and operational dashboards.",
                    "Numeric or tabular intent detected",
                    30,
                    600,
                    25,
                    0.00005,
                    0.00010
            )
    );

    private final StringRedisTemplate redis;
    private final RedisSearchHelper redisSearchHelper;

    public AiGatewayService(StringRedisTemplate redis, RedisSearchHelper redisSearchHelper) {
        this.redis = redis;
        this.redisSearchHelper = redisSearchHelper;
    }

    public void init() {
        RedisVectorOps.dropIndex(redis, ROUTE_INDEX);
        RedisVectorOps.dropIndex(redis, CACHE_INDEX);
        RedisVectorOps.createVectorIndex(redis, ROUTE_INDEX, ROUTE_PREFIX,
                "modelId TEXT modelTag TAG label TEXT capability TEXT rationale TEXT", VECTOR_DIM);
        RedisVectorOps.createVectorIndex(redis, CACHE_INDEX, CACHE_PREFIX,
                "modelId TEXT modelTag TAG question TEXT response TEXT ttlSeconds NUMERIC createdAt TEXT", VECTOR_DIM);
    }

    public void seedDemoData() {
        for (ModelConfig config : MODEL_CONFIGS) {
            String key = ROUTE_PREFIX + config.tag();
            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("modelId", config.id());
            hash.put("modelTag", config.tag());
            hash.put("label", config.label());
            hash.put("capability", config.capability());
            hash.put("rationale", config.routingReason());
            redis.opsForHash().putAll(key, hash);
            RedisVectorOps.storeVectorField(redis, key, embed(config.routingPrompt()));
        }

        seedCacheEntry(getModel("gpt4o"), "Explain Basel III capital requirements",
                "Basel III strengthens bank resilience by increasing CET1 capital quality, introducing capital conservation buffers, and enforcing liquidity and leverage constraints. For a workshop demo, the key message is that higher-quality capital plus better liquidity observability reduces systemic risk.");
        seedCacheEntry(getModel("gpt4omini"), "What is PSD2?",
                "PSD2 is the EU Payments Services Directive that opened banking APIs to regulated third parties and introduced Strong Customer Authentication for many electronic payments.");
        seedCacheEntry(getModel("internalnumeric"), "Show capital ratio by quarter for 2024",
                "Quarterly capital ratios (demo): Q1 13.2%, Q2 13.4%, Q3 13.7%, Q4 14.0%. Trend: +0.8 percentage points over the year.");
    }

    public Map<String, Object> handleQuery(String query, String userId, String sessionId) {
        float[] queryVector = embed(query);

        long routeStart = System.nanoTime();
        RouteDecision route = routeQuery(query, queryVector);
        long routingMs = elapsedMs(routeStart);

        long cacheStart = System.nanoTime();
        CacheResult cacheResult = checkSemanticCache(route.model(), queryVector);
        long cacheMs = elapsedMs(cacheStart);

        Map<String, Object> rateLimit;
        String response;
        long modelMs = 0L;
        long inputTokens = estimateTokens(query);
        long outputTokens = 0L;
        double estimatedCostUsd = 0d;
        boolean rateLimited = false;

        long rateLimitStart = System.nanoTime();
        if (cacheResult.hit()) {
            rateLimit = getRateLimitStatus(route.model());
            response = cacheResult.response();
        } else {
            rateLimit = consumeRateLimit(route.model());
            if (!Boolean.TRUE.equals(rateLimit.get("allowed"))) {
                rateLimited = true;
                response = "Provider budget exhausted for " + route.model().label() + ". Retry after the current window resets or route a different query.";
            } else {
                response = generateMockResponse(route.model(), query);
                outputTokens = estimateTokens(response);
                estimatedCostUsd = estimateCost(route.model(), inputTokens, outputTokens);
                modelMs = simulateModelLatency(route.model(), query);
                storeCacheEntry(route.model(), query, response);
                updateSessionUsage(sessionId, route.model(), inputTokens, outputTokens, estimatedCostUsd);
            }
        }
        long rateLimitMs = elapsedMs(rateLimitStart);

        long statsStart = System.nanoTime();
        long totalMs = routingMs + cacheMs + rateLimitMs + modelMs;
        recordStats(route.model(), cacheResult.hit(), rateLimited, totalMs, estimatedCostUsd, inputTokens + outputTokens);
        long statsMs = elapsedMs(statsStart);

        long logStart = System.nanoTime();
        logRequest(query, userId, sessionId, route, cacheResult, rateLimited, totalMs + statsMs,
                estimatedCostUsd, response, rateLimit, modelMs);
        long logMs = elapsedMs(logStart);
        totalMs += statsMs + logMs;

        Map<String, Object> sessionUsage = getSessionUsage(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("userId", userId);
        result.put("sessionId", sessionId);
        result.put("modelId", route.model().id());
        result.put("model", route.model().label());
        result.put("cacheHit", cacheResult.hit());
        result.put("rateLimited", rateLimited);
        result.put("response", response);
        result.put("route", Map.of(
                "modelId", route.model().id(),
                "model", route.model().label(),
                "capability", route.model().capability(),
                "reason", route.reason(),
                "distance", round(route.distance())
        ));
        result.put("cache", Map.of(
                "hit", cacheResult.hit(),
                "distance", round(cacheResult.distance()),
                "threshold", CACHE_DISTANCE_THRESHOLD,
                "matchedQuestion", cacheResult.question(),
                "ttlSeconds", route.model().cacheTtlSeconds()
        ));
        result.put("rateLimit", rateLimit);
        result.put("cost", Map.of(
                "inputTokens", inputTokens,
                "outputTokens", outputTokens,
                "estimatedCostUsd", round(estimatedCostUsd),
                "sessionTotalUsd", round(asDouble(sessionUsage.get("totalCostUsd"))),
                "sessionTotalTokens", asLong(sessionUsage.get("totalTokens"))
        ));
        result.put("latency", Map.of(
                "routingMs", routingMs,
                "cacheMs", cacheMs,
                "rateLimitMs", rateLimitMs,
                "modelMs", modelMs,
                "statsMs", statsMs,
                "logMs", logMs,
                "totalMs", totalMs
        ));
        if (rateLimited) {
            result.put("error", "Rate limit exceeded for " + route.model().label());
        }
        return result;
    }

    public Map<String, Object> getStats() {
        List<Map<String, Object>> models = new ArrayList<>();
        long totalRequests = 0L;
        long totalCacheHits = 0L;
        double totalCost = 0d;

        for (ModelConfig config : MODEL_CONFIGS) {
            Map<Object, Object> raw = redis.opsForHash().entries(STATS_PREFIX + config.tag());
            long requests = asLong(raw.get("requests"));
            long cacheHits = asLong(raw.get("cacheHits"));
            long rateLimited = asLong(raw.get("rateLimited"));
            long totalLatencyMs = asLong(raw.get("totalLatencyMs"));
            double costUsd = asDouble(raw.get("totalCostUsd"));
            long totalTokens = asLong(raw.get("totalTokens"));

            long currentCount = getCurrentRateLimitCount(config.tag());
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("modelId", config.id());
            model.put("model", config.label());
            model.put("capability", config.capability());
            model.put("requests", requests);
            model.put("cacheHits", cacheHits);
            model.put("rateLimited", rateLimited);
            model.put("cacheHitRate", requests > 0 ? round((cacheHits * 100.0d) / requests) : 0d);
            model.put("totalCostUsd", round(costUsd));
            model.put("avgLatencyMs", requests > 0 ? round((double) totalLatencyMs / requests) : 0d);
            model.put("totalTokens", totalTokens);
            model.put("cacheTtlSeconds", config.cacheTtlSeconds());
            model.put("rateLimitPerMinute", config.rateLimitPerMinute());
            model.put("remaining", Math.max(0, config.rateLimitPerMinute() - currentCount));
            model.put("cachedEntries", countKeys(CACHE_PREFIX + config.tag() + ":*"));
            models.add(model);

            totalRequests += requests;
            totalCacheHits += cacheHits;
            totalCost += costUsd;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", models);
        result.put("totalRequests", totalRequests);
        result.put("totalCacheHits", totalCacheHits);
        result.put("overallCacheHitRate", totalRequests > 0 ? round((totalCacheHits * 100.0d) / totalRequests) : 0d);
        result.put("totalCostUsd", round(totalCost));
        result.put("logEntries", getStreamSize());
        return result;
    }

    public Map<String, Object> getRequestLog(int limit) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().reverseRange(
                STREAM_KEY, Range.unbounded(), Limit.limit().count(limit));

        if (records == null) {
            records = Collections.emptyList();
        }

        for (MapRecord<String, Object, Object> record : records) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", record.getId().getValue());
            entry.put("timestamp", Instant.ofEpochMilli(parseTimestamp(record.getId().getValue())).toString());
            for (Map.Entry<Object, Object> value : record.getValue().entrySet()) {
                entry.put(String.valueOf(value.getKey()), value.getValue());
            }
            entries.add(entry);
        }

        return Map.of("count", entries.size(), "entries", entries);
    }

    public void reset() {
        Set<String> keys = new LinkedHashSet<>();
        Set<String> scanned = RedisScanHelper.scanKeys(redis, "uc16:*");
        if (scanned != null) {
            keys.addAll(scanned);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        init();
        seedDemoData();
    }

    private RouteDecision routeQuery(String query, float[] queryVector) {
        byte[] vectorBytes = RedisSearchHelper.vectorToBytes(queryVector);
        byte[][] args = new byte[][] {
                "*=>[KNN 1 @vector $BLOB]".getBytes(StandardCharsets.UTF_8),
                "PARAMS".getBytes(StandardCharsets.UTF_8),
                "2".getBytes(StandardCharsets.UTF_8),
                "BLOB".getBytes(StandardCharsets.UTF_8),
                vectorBytes,
                "RETURN".getBytes(StandardCharsets.UTF_8),
                "5".getBytes(StandardCharsets.UTF_8),
                "modelId".getBytes(StandardCharsets.UTF_8),
                "modelTag".getBytes(StandardCharsets.UTF_8),
                "label".getBytes(StandardCharsets.UTF_8),
                "capability".getBytes(StandardCharsets.UTF_8),
                "rationale".getBytes(StandardCharsets.UTF_8),
                "DIALECT".getBytes(StandardCharsets.UTF_8),
                "2".getBytes(StandardCharsets.UTF_8)
        };
        List<Map<String, String>> results = redisSearchHelper.parseSearchResults(
                redisSearchHelper.ftSearchWithBinaryArgs(ROUTE_INDEX, args));

        ModelConfig model = MODEL_CONFIGS.get(0);
        String reason = model.routingReason();
        if (!results.isEmpty()) {
            Map<String, String> top = results.get(0);
            model = getModel(top.getOrDefault("modelTag", MODEL_CONFIGS.get(0).tag()));
            reason = top.getOrDefault("rationale", model.routingReason());
        }

        return new RouteDecision(model, reason, cosineDistance(queryVector, embed(model.routingPrompt())));
    }

    private CacheResult checkSemanticCache(ModelConfig model, float[] queryVector) {
        byte[] vectorBytes = RedisSearchHelper.vectorToBytes(queryVector);
        String knnQuery = "@modelTag:{" + model.tag() + "}=>[KNN 1 @vector $BLOB]";
        byte[][] args = new byte[][] {
                knnQuery.getBytes(StandardCharsets.UTF_8),
                "PARAMS".getBytes(StandardCharsets.UTF_8),
                "2".getBytes(StandardCharsets.UTF_8),
                "BLOB".getBytes(StandardCharsets.UTF_8),
                vectorBytes,
                "RETURN".getBytes(StandardCharsets.UTF_8),
                "4".getBytes(StandardCharsets.UTF_8),
                "question".getBytes(StandardCharsets.UTF_8),
                "response".getBytes(StandardCharsets.UTF_8),
                "modelId".getBytes(StandardCharsets.UTF_8),
                "createdAt".getBytes(StandardCharsets.UTF_8),
                "DIALECT".getBytes(StandardCharsets.UTF_8),
                "2".getBytes(StandardCharsets.UTF_8)
        };
        List<Map<String, String>> results = redisSearchHelper.parseSearchResults(
                redisSearchHelper.ftSearchWithBinaryArgs(CACHE_INDEX, args));
        if (results.isEmpty()) {
            return CacheResult.miss();
        }

        Map<String, String> top = results.get(0);
        String matchedQuestion = top.getOrDefault("question", "");
        double distance = cosineDistance(queryVector, embed(matchedQuestion));
        if (distance > CACHE_DISTANCE_THRESHOLD) {
            return CacheResult.miss();
        }

        return new CacheResult(true, matchedQuestion, top.getOrDefault("response", ""), distance);
    }

    private void storeCacheEntry(ModelConfig model, String query, String response) {
        String key = CACHE_PREFIX + model.tag() + ":" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("modelId", model.id());
        hash.put("modelTag", model.tag());
        hash.put("question", query);
        hash.put("response", response);
        hash.put("ttlSeconds", String.valueOf(model.cacheTtlSeconds()));
        hash.put("createdAt", Instant.now().toString());
        redis.opsForHash().putAll(key, hash);
        RedisVectorOps.storeVectorField(redis, key, embed(query));
        redis.expire(key, model.cacheTtlSeconds(), TimeUnit.SECONDS);
    }

    private void seedCacheEntry(ModelConfig model, String query, String response) {
        if (model == null) {
            return;
        }
        storeCacheEntry(model, query, response);
    }

    private Map<String, Object> consumeRateLimit(ModelConfig model) {
        String key = RATE_LIMIT_PREFIX + model.tag();
        Long currentCount = redis.opsForValue().increment(key);
        if (currentCount == null) {
            currentCount = 1L;
        }
        if (currentCount == 1L) {
            redis.expire(key, 60, TimeUnit.SECONDS);
        }

        long ttl = getRateLimitTtl(key);
        boolean allowed = currentCount <= model.rateLimitPerMinute();
        return Map.of(
                "allowed", allowed,
                "limit", model.rateLimitPerMinute(),
                "currentCount", currentCount,
                "remaining", Math.max(0, model.rateLimitPerMinute() - currentCount),
                "retryAfter", allowed ? 0 : ttl,
                "ttl", ttl,
                "model", model.label()
        );
    }

    private Map<String, Object> getRateLimitStatus(ModelConfig model) {
        String key = RATE_LIMIT_PREFIX + model.tag();
        long currentCount = getCurrentRateLimitCount(model.tag());
        return Map.of(
                "allowed", true,
                "limit", model.rateLimitPerMinute(),
                "currentCount", currentCount,
                "remaining", Math.max(0, model.rateLimitPerMinute() - currentCount),
                "retryAfter", 0,
                "ttl", getRateLimitTtl(key),
                "model", model.label()
        );
    }

    private void updateSessionUsage(String sessionId, ModelConfig model, long inputTokens,
                                    long outputTokens, double estimatedCostUsd) {
        String key = USAGE_PREFIX + sessionId;
        redis.opsForHash().increment(key, model.tag() + ":requests", 1);
        redis.opsForHash().increment(key, model.tag() + ":tokens", inputTokens + outputTokens);
        redis.opsForHash().increment(key, model.tag() + ":costUsd", estimatedCostUsd);
        redis.opsForHash().increment(key, "totalTokens", inputTokens + outputTokens);
        redis.opsForHash().increment(key, "totalCostUsd", estimatedCostUsd);
        redis.expire(key, 30, TimeUnit.MINUTES);
    }

    private Map<String, Object> getSessionUsage(String sessionId) {
        Map<Object, Object> raw = redis.opsForHash().entries(USAGE_PREFIX + sessionId);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("totalTokens", asLong(raw.get("totalTokens")));
        usage.put("totalCostUsd", asDouble(raw.get("totalCostUsd")));
        return usage;
    }

    private void recordStats(ModelConfig model, boolean cacheHit, boolean rateLimited,
                             long latencyMs, double costUsd, long totalTokens) {
        String key = STATS_PREFIX + model.tag();
        redis.opsForHash().increment(key, "requests", 1);
        if (cacheHit) {
            redis.opsForHash().increment(key, "cacheHits", 1);
        }
        if (rateLimited) {
            redis.opsForHash().increment(key, "rateLimited", 1);
        }
        redis.opsForHash().increment(key, "totalLatencyMs", latencyMs);
        redis.opsForHash().increment(key, "totalCostUsd", costUsd);
        redis.opsForHash().increment(key, "totalTokens", totalTokens);
    }

    private void logRequest(String query, String userId, String sessionId, RouteDecision route,
                            CacheResult cacheResult, boolean rateLimited, long latencyMs,
                            double costUsd, String response, Map<String, Object> rateLimit,
                            long modelMs) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("userId", userId);
        fields.put("sessionId", sessionId);
        fields.put("modelId", route.model().id());
        fields.put("model", route.model().label());
        fields.put("cacheHit", String.valueOf(cacheResult.hit()));
        fields.put("rateLimited", String.valueOf(rateLimited));
        fields.put("latencyMs", String.valueOf(latencyMs));
        fields.put("modelMs", String.valueOf(modelMs));
        fields.put("costUsd", String.format(Locale.US, "%.6f", costUsd));
        fields.put("routeDistance", String.format(Locale.US, "%.4f", route.distance()));
        fields.put("cacheDistance", String.format(Locale.US, "%.4f", cacheResult.distance()));
        fields.put("remaining", String.valueOf(rateLimit.getOrDefault("remaining", 0)));
        fields.put("query", truncate(query, 140));
        fields.put("response", truncate(response, 180));
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(STREAM_KEY));
        redis.opsForStream().trim(STREAM_KEY, MAX_STREAM_LEN);
    }

    private String generateMockResponse(ModelConfig model, String query) {
        if ("internalnumeric".equals(model.tag())) {
            int base = Math.abs(query.toLowerCase(Locale.ROOT).hashCode());
            double q1 = 11.5 + (base % 40) / 10.0;
            double q2 = q1 + 0.3;
            double q3 = q2 + 0.2;
            double q4 = q3 + 0.4;
            return "Internal gateway result for numeric/tabular intent:\n"
                    + "• Query: " + query + "\n"
                    + String.format(Locale.US,
                    "• Demo metrics — Q1 %.1f%% | Q2 %.1f%% | Q3 %.1f%% | Q4 %.1f%%%n• Delta vs Q1: +%.1fpp%n• Recommended route: keep this request on the internal numeric model for low-latency structured output.",
                    q1, q2, q3, q4, q4 - q1);
        }
        if ("gpt4o".equals(model.tag())) {
            return "Gateway routed this request to GPT-4o because it looks policy-heavy and multi-step. "
                    + "For \"" + query + "\", the key takeaway is that the answer needs explanation, trade-offs, and regulatory context rather than a short factual lookup.";
        }
        return "Gateway routed this request to GPT-4o-mini for a fast FAQ-style answer. "
                + "For \"" + query + "\", the demo response is concise, low-cost, and optimized for short user-facing explanations.";
    }

    private long estimateTokens(String text) {
        return text == null || text.isBlank() ? 0L : Math.max(1, text.length() / 4L);
    }

    private double estimateCost(ModelConfig model, long inputTokens, long outputTokens) {
        return ((inputTokens / 1000.0d) * model.inputCostPer1k())
                + ((outputTokens / 1000.0d) * model.outputCostPer1k());
    }

    private long simulateModelLatency(ModelConfig model, String query) {
        int jitter = Math.abs(query.hashCode()) % 25;
        return model.baseModelLatencyMs() + jitter;
    }

    private float[] embed(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        float[] vector = new float[VECTOR_DIM];

        addKeywordWeight(lower, vector, 0, 2.2f, "basel", "mifid", "gdpr", "psd2", "regulation", "regulatory", "capital", "compliance", "governance", "policy", "architecture", "trade-off", "reasoning", "explain", "analyze", "analyse", "compare", "summarize", "implications", "guardrail", "risk");
        addKeywordWeight(lower, vector, 1, 1.8f, "what is", "what are", "define", "faq", "support", "how do", "can i", "quick", "simple", "list", "product", "feature", "customer");
        addKeywordWeight(lower, vector, 2, 2.4f, "ratio", "quarter", "monthly", "weekly", "metric", "kpi", "calculate", "calculation", "numeric", "number", "table", "tabular", "csv", "percentage", "limit", "latency", "cost", "volume", "throughput", "balance");
        addKeywordWeight(lower, vector, 3, 1.2f, "redis", "gateway", "provider", "routing", "route", "model", "cache", "semantic", "observability", "stream", "rate limit");
        addKeywordWeight(lower, vector, 4, 1.1f, "account", "card", "loan", "mortgage", "savings", "transfer", "payment", "atm", "branch", "portfolio");
        addKeywordWeight(lower, vector, 5, 1.4f, "why", "recommend", "because", "decision", "impact", "tradeoff", "trade-off", "best", "safest");
        addKeywordWeight(lower, vector, 6, 1.3f, "show", "report", "dashboard", "trend", "compare", "rows", "columns", "dataset", "series");
        addKeywordWeight(lower, vector, 7, 0.9f, "who", "when", "where", "which");

        if (lower.matches(".*\\d.*")) {
            vector[2] += 2.0f;
            vector[6] += 0.8f;
        }
        int tokenCount = lower.isBlank() ? 0 : lower.split("\\s+").length;
        if (tokenCount > 10) {
            vector[0] += 1.0f;
            vector[5] += 0.5f;
        }
        if (tokenCount <= 6) {
            vector[1] += 0.8f;
            vector[7] += 0.3f;
        }
        if (vectorMagnitude(vector) == 0d) {
            vector[1] = 1.0f;
            vector[4] = 0.5f;
        }
        normalize(vector);
        return vector;
    }

    private void addKeywordWeight(String text, float[] vector, int idx, float weight, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                vector[idx] += weight;
            }
        }
    }

    private void normalize(float[] vector) {
        double magnitude = vectorMagnitude(vector);
        if (magnitude == 0d) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= (float) magnitude;
        }
    }

    private double vectorMagnitude(float[] vector) {
        double sum = 0d;
        for (float v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    private double cosineDistance(float[] left, float[] right) {
        double dot = 0d;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
        }
        return 1.0d - Math.max(-1.0d, Math.min(1.0d, dot));
    }

    private ModelConfig getModel(String tag) {
        for (ModelConfig config : MODEL_CONFIGS) {
            if (config.tag().equals(tag)) {
                return config;
            }
        }
        return MODEL_CONFIGS.get(0);
    }

    private long getCurrentRateLimitCount(String modelTag) {
        String raw = redis.opsForValue().get(RATE_LIMIT_PREFIX + modelTag);
        return raw == null ? 0L : Long.parseLong(raw);
    }

    private long getRateLimitTtl(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? 0L : ttl;
    }

    private int countKeys(String pattern) {
        Set<String> keys = RedisScanHelper.scanKeys(redis, pattern);
        return keys == null ? 0 : keys.size();
    }

    private long getStreamSize() {
        Long size = redis.opsForStream().size(STREAM_KEY);
        return size == null ? 0L : size;
    }

    private long parseTimestamp(String streamId) {
        int dash = streamId.indexOf('-');
        return Long.parseLong(dash > 0 ? streamId.substring(0, dash) : streamId);
    }

    private long elapsedMs(long startedAtNs) {
        return Math.max(0L, (System.nanoTime() - startedAtNs) / 1_000_000L);
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    private double asDouble(Object value) {
        if (value == null) {
            return 0d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0d;
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLen - 1) + "…";
    }

    private record ModelConfig(String id, String tag, String label, String capability,
                               String routingPrompt, String routingReason, int rateLimitPerMinute,
                               int cacheTtlSeconds, int baseModelLatencyMs,
                               double inputCostPer1k, double outputCostPer1k) {
    }

    private record RouteDecision(ModelConfig model, String reason, double distance) {
    }

    private record CacheResult(boolean hit, String question, String response, double distance) {
        private static CacheResult miss() {
            return new CacheResult(false, "", "", 1.0d);
        }
    }
}