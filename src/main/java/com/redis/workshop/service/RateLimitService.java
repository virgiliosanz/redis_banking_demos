package com.redis.workshop.service;

import com.redis.workshop.config.RedisCommandLogger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Rate Limiting Service — Fixed-window pattern using Redis INCR + EXPIRE.
 * Banking context: Open Banking API protection (PSD2 compliance).
 *
 * Key pattern: workshop:ratelimit:{clientId}
 * Window: 60 seconds, max 10 requests per window (demo-friendly values).
 */
@Service
public class RateLimitService {

    private static final String KEY_PREFIX = "workshop:ratelimit:";
    private static final int MAX_REQUESTS = 10;
    private static final int WINDOW_SECONDS = 60;

    private final StringRedisTemplate redis;
    private final RedisCommandLogger commandLogger;

    public RateLimitService(StringRedisTemplate redis, RedisCommandLogger commandLogger) {
        this.redis = redis;
        this.commandLogger = commandLogger;
    }

    /**
     * Check and consume one request from the rate limit budget.
     * Uses INCR + EXPIRE (fixed-window pattern).
     *
     * @param clientId the API client identifier
     * @return map with allowed (boolean), remaining, limit, retryAfter, currentCount
     */
    public Map<String, Object> checkRateLimit(String clientId) {
        String key = KEY_PREFIX + clientId;

        // INCR atomically increments and returns new value (creates key with value 1 if absent)
        Long currentCount = redis.opsForValue().increment(key);
        if (currentCount == null) {
            currentCount = 1L;
        }
        commandLogger.log("UC4", "INCR", key, "count=" + currentCount,
                "INCR " + key,
                "(integer) " + currentCount + (currentCount == 1 ? " (key created)" : ""));

        // Set TTL only on the first request of a new window
        if (currentCount == 1) {
            redis.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            commandLogger.log("UC4", "EXPIRE", key, WINDOW_SECONDS + "s",
                    "EXPIRE " + key + " " + WINDOW_SECONDS,
                    "(integer) 1");
        }

        boolean allowed = currentCount <= MAX_REQUESTS;
        long remaining = Math.max(0, MAX_REQUESTS - currentCount);

        // Get TTL to show when the window resets
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            ttl = (long) WINDOW_SECONDS;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("allowed", allowed);
        result.put("remaining", remaining);
        result.put("limit", MAX_REQUESTS);
        result.put("currentCount", currentCount);
        result.put("windowSeconds", WINDOW_SECONDS);
        result.put("retryAfter", allowed ? 0 : ttl);
        result.put("ttl", ttl);
        result.put("clientId", clientId);

        return result;
    }

    /**
     * Get current rate limit status without consuming a request.
     */
    public Map<String, Object> getStatus(String clientId) {
        String key = KEY_PREFIX + clientId;

        String val = redis.opsForValue().get(key);
        long currentCount = val != null ? Long.parseLong(val) : 0;
        long remaining = Math.max(0, MAX_REQUESTS - currentCount);

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            ttl = 0L;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("remaining", remaining);
        result.put("limit", MAX_REQUESTS);
        result.put("currentCount", currentCount);
        result.put("windowSeconds", WINDOW_SECONDS);
        result.put("ttl", ttl);
        result.put("clientId", clientId);
        result.put("active", val != null);

        return result;
    }

    /**
     * Reset the rate limit for a client (for demo purposes).
     */
    public void reset(String clientId) {
        redis.delete(KEY_PREFIX + clientId);
    }
}
