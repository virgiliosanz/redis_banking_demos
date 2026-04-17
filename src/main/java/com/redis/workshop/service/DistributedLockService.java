package com.redis.workshop.service;

import com.redis.workshop.config.RedisCommandLogger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * UC13: Distributed Locking for Banking Operations.
 * Demonstrates SET NX EX for lock acquisition and Lua script for safe release.
 * Mock scenario: "Account Transfer Lock" — lock an account during a wire transfer.
 */
@Service
public class DistributedLockService {

    private static final String LOCK_PREFIX = "workshop:lock:";

    /**
     * Lua script for safe lock release.
     * Only deletes the key if the stored value matches the expected clientId.
     * This prevents a client from accidentally releasing another client's lock.
     */
    private static final String RELEASE_LOCK_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('DEL', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(RELEASE_LOCK_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final RedisCommandLogger commandLogger;

    public DistributedLockService(StringRedisTemplate redis, RedisCommandLogger commandLogger) {
        this.redis = redis;
        this.commandLogger = commandLogger;
    }

    /**
     * Acquire a distributed lock using SET NX EX.
     * Returns lock info if acquired, or contention info if already held.
     */
    public Map<String, Object> acquireLock(String resourceId, String clientId, int ttlSeconds) {
        String key = LOCK_PREFIX + resourceId;

        // SET key clientId NX EX ttl
        Boolean acquired = redis.opsForValue().setIfAbsent(key, clientId,
                java.time.Duration.ofSeconds(ttlSeconds));
        commandLogger.log("UC13", "SET NX EX", key,
                "clientId=" + clientId + " " + (Boolean.TRUE.equals(acquired) ? "ACQUIRED" : "DENIED"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceId", resourceId);
        result.put("clientId", clientId);
        result.put("redisKey", key);
        result.put("redisCommand", "SET " + key + " " + clientId + " NX EX " + ttlSeconds);

        if (Boolean.TRUE.equals(acquired)) {
            result.put("acquired", true);
            result.put("ttl", ttlSeconds);
            result.put("message", "Lock acquired successfully");
        } else {
            // Lock already held — get current holder info
            String currentHolder = redis.opsForValue().get(key);
            Long remainingTtl = redis.getExpire(key, TimeUnit.SECONDS);
            result.put("acquired", false);
            result.put("currentHolder", currentHolder);
            result.put("remainingTtl", remainingTtl != null ? remainingTtl : -1);
            result.put("message", "Lock already held by " + currentHolder);
        }
        return result;
    }

    /**
     * Release a lock safely using Lua script.
     * Only releases if the caller is the current lock holder.
     */
    public Map<String, Object> releaseLock(String resourceId, String clientId) {
        String key = LOCK_PREFIX + resourceId;

        Long released = redis.execute(RELEASE_SCRIPT,
                Collections.singletonList(key), clientId);
        commandLogger.log("UC13", "EVAL (Lua release)", key,
                "clientId=" + clientId + " " + (released != null && released == 1L ? "RELEASED" : "DENIED"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceId", resourceId);
        result.put("clientId", clientId);
        result.put("redisKey", key);
        result.put("luaScript", RELEASE_LOCK_LUA);

        if (released != null && released == 1L) {
            result.put("released", true);
            result.put("message", "Lock released successfully");
        } else {
            result.put("released", false);
            result.put("message", "Lock not held by " + clientId + " — release denied");
        }
        return result;
    }

    /**
     * Get lock info: current holder and remaining TTL.
     */
    public Map<String, Object> getLockInfo(String resourceId) {
        String key = LOCK_PREFIX + resourceId;
        String holder = redis.opsForValue().get(key);
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceId", resourceId);
        result.put("redisKey", key);

        if (holder != null) {
            result.put("locked", true);
            result.put("holder", holder);
            result.put("ttl", ttl != null ? ttl : -1);
        } else {
            result.put("locked", false);
            result.put("holder", null);
            result.put("ttl", -2);
        }
        return result;
    }

    /**
     * Simulate contention: 3 concurrent "clients" try to acquire the same lock.
     * Demonstrates the mutual exclusion guarantee of SET NX.
     */
    public Map<String, Object> simulateContention(String resourceId) {
        String key = LOCK_PREFIX + resourceId;
        // First, ensure the lock is free for the demo
        redis.delete(key);

        String[] clients = {"transfer-svc-A", "transfer-svc-B", "transfer-svc-C"};
        int ttl = 30;

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();

        for (String client : clients) {
            futures.add(executor.submit(() -> acquireLock(resourceId, client, ttl)));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        String winner = null;
        for (int i = 0; i < futures.size(); i++) {
            try {
                Map<String, Object> r = futures.get(i).get(5, TimeUnit.SECONDS);
                r.put("client", clients[i]);
                results.add(r);
                if (Boolean.TRUE.equals(r.get("acquired"))) {
                    winner = clients[i];
                }
            } catch (Exception e) {
                results.add(Map.of("client", clients[i], "error", e.getMessage()));
            }
        }
        executor.shutdown();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceId", resourceId);
        result.put("redisKey", key);
        result.put("winner", winner);
        result.put("attempts", results);
        result.put("message", winner + " won the lock — others were denied (NX guarantee)");
        return result;
    }
}
