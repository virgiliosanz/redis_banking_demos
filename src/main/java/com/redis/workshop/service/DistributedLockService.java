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

    private static final String LOCK_PREFIX = "uc13:lock:";

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
        commandLogger.startCapture();
        String key = LOCK_PREFIX + resourceId;

        // SET key clientId NX EX ttl
        Boolean acquired = redis.opsForValue().setIfAbsent(key, clientId,
                java.time.Duration.ofSeconds(ttlSeconds));
        commandLogger.log("UC13", "SET NX EX", key,
                "clientId=" + clientId + " " + (Boolean.TRUE.equals(acquired) ? "ACQUIRED" : "DENIED"),
                "SET " + key + " " + clientId + " NX EX " + ttlSeconds,
                Boolean.TRUE.equals(acquired) ? "OK (lock acquired)"
                        : "nil (lock already held by other client)");

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
            commandLogger.log("UC13", "GET", key, null,
                    "GET " + key,
                    currentHolder != null ? "\"" + currentHolder + "\" (current holder)" : "(nil)");
            Long remainingTtl = redis.getExpire(key, TimeUnit.SECONDS);
            commandLogger.log("UC13", "TTL", key, null,
                    "TTL " + key,
                    "(integer) " + (remainingTtl != null ? remainingTtl : -1));
            result.put("acquired", false);
            result.put("currentHolder", currentHolder);
            result.put("remainingTtl", remainingTtl != null ? remainingTtl : -1);
            result.put("message", "Lock already held by " + currentHolder);
        }
        result.put("redisCommands", commandLogger.getCaptured());
        return result;
    }

    /**
     * Release a lock safely using Lua script.
     * Only releases if the caller is the current lock holder.
     */
    public Map<String, Object> releaseLock(String resourceId, String clientId) {
        commandLogger.startCapture();
        String key = LOCK_PREFIX + resourceId;

        Long released = redis.execute(RELEASE_SCRIPT,
                Collections.singletonList(key), clientId);
        boolean ok = released != null && released == 1L;
        commandLogger.log("UC13", "EVAL (Lua release)", key,
                "clientId=" + clientId + " " + (ok ? "RELEASED" : "DENIED"),
                "EVAL \"if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end\" 1 "
                        + key + " " + clientId,
                ok ? "(integer) 1 (lock released)"
                        : "(integer) 0 (caller is not the lock holder — denied)");

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
        result.put("redisCommands", commandLogger.getCaptured());
        return result;
    }

    /**
     * Get lock info: current holder and remaining TTL.
     */
    public Map<String, Object> getLockInfo(String resourceId) {
        commandLogger.startCapture();
        String key = LOCK_PREFIX + resourceId;
        String holder = redis.opsForValue().get(key);
        commandLogger.log("UC13", "GET", key, null,
                "GET " + key,
                holder != null ? "\"" + holder + "\"" : "(nil)");
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        commandLogger.log("UC13", "TTL", key, null,
                "TTL " + key,
                "(integer) " + (ttl != null ? ttl : -2));

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
        result.put("redisCommands", commandLogger.getCaptured());
        return result;
    }

    /**
     * Simulate contention: 3 concurrent "clients" try to acquire the same lock.
     * Demonstrates the mutual exclusion guarantee of SET NX.
     */
    public Map<String, Object> simulateContention(String resourceId) {
        commandLogger.startCapture();
        String key = LOCK_PREFIX + resourceId;
        // First, ensure the lock is free for the demo
        redis.delete(key);
        commandLogger.log("UC13", "DEL", key, "reset contention demo",
                "DEL " + key,
                "OK (lock reset before demo)");

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

        // Aggregate per-attempt redisCommands into a single flat list for the card
        List<String> aggregated = new ArrayList<>(commandLogger.getCaptured());
        for (Map<String, Object> r : results) {
            Object cmds = r.get("redisCommands");
            if (cmds instanceof List<?> list) {
                for (Object s : list) {
                    if (s instanceof String str) aggregated.add(str);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceId", resourceId);
        result.put("redisKey", key);
        result.put("winner", winner);
        result.put("attempts", results);
        result.put("message", winner + " won the lock — others were denied (NX guarantee)");
        result.put("redisCommands", aggregated);
        return result;
    }
}
