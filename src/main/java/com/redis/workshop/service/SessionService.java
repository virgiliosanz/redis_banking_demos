package com.redis.workshop.service;

import com.redis.workshop.config.RedisCommandLogger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class SessionService {

    private static final long SESSION_TTL_SECONDS = 300; // 5 minutes for demo
    private static final String SESSION_PREFIX = "uc2:user:";
    private static final String TOKEN_PREFIX = "uc2:token:";

    // Mock users: username -> password
    private static final Map<String, String> MOCK_USERS = Map.of(
            "user1", "password1",
            "user2", "password2"
    );

    // Mock user profiles
    private static final Map<String, Map<String, String>> USER_PROFILES = Map.of(
            "user1", Map.of("fullName", "Ana García López", "email", "ana.garcia@banco.es",
                    "role", "Premium Client", "accountId", "ES76-0128-0001-23-0100012345"),
            "user2", Map.of("fullName", "Carlos Ruiz Fernández", "email", "carlos.ruiz@banco.es",
                    "role", "Business Client", "accountId", "ES91-0128-0002-45-0200067890")
    );

    private final StringRedisTemplate redis;
    private final RedisCommandLogger commandLogger;

    public SessionService(StringRedisTemplate redis, RedisCommandLogger commandLogger) {
        this.redis = redis;
        this.commandLogger = commandLogger;
    }

    /**
     * Authenticate user and create session + auth token in Redis.
     * Returns session info on success, null on failure.
     */
    public Map<String, Object> login(String username, String password) {
        commandLogger.startCapture();
        // Validate credentials
        String expected = MOCK_USERS.get(username);
        if (expected == null || !expected.equals(password)) {
            commandLogger.getCaptured();
            return null;
        }

        // Generate auth token
        String token = UUID.randomUUID().toString();
        String sessionKey = SESSION_PREFIX + username;
        String tokenKey = TOKEN_PREFIX + token;

        // Build session data
        Map<String, String> profile = USER_PROFILES.get(username);
        Map<String, String> sessionData = new HashMap<>();
        sessionData.put("username", username);
        sessionData.put("fullName", profile.get("fullName"));
        sessionData.put("email", profile.get("email"));
        sessionData.put("role", profile.get("role"));
        sessionData.put("accountId", profile.get("accountId"));
        sessionData.put("token", token);
        sessionData.put("createdAt", String.valueOf(System.currentTimeMillis()));
        sessionData.put("ipAddress", "192.168.1." + (int) (Math.random() * 254 + 1));

        // HSET — store session as Redis Hash
        redis.opsForHash().putAll(sessionKey, sessionData);
        commandLogger.log("UC2", "HSET", sessionKey, "fields=" + sessionData.size(),
                "HSET " + sessionKey + " username " + username + " fullName \""
                        + profile.get("fullName") + "\" token " + token + " ... ("
                        + sessionData.size() + " fields)",
                "(integer) " + sessionData.size() + " (fields added)");
        // EXPIRE — set TTL on session key
        redis.expire(sessionKey, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        commandLogger.log("UC2", "EXPIRE", sessionKey, SESSION_TTL_SECONDS + "s",
                "EXPIRE " + sessionKey + " " + SESSION_TTL_SECONDS,
                "(integer) 1");

        // SET — store token pointing back to username
        redis.opsForValue().set(tokenKey, username, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        commandLogger.log("UC2", "SET EX", tokenKey, "user=" + username,
                "SET " + tokenKey + " " + username + " EX " + SESSION_TTL_SECONDS,
                "OK");

        Map<String, Object> result = new HashMap<>(sessionData);
        result.put("sessionKey", sessionKey);
        result.put("tokenKey", tokenKey);
        result.put("ttl", SESSION_TTL_SECONDS);
        result.put("redisCommands", commandLogger.getCaptured());
        return result;
    }

    /**
     * Get session info for a user.
     */
    public Map<String, Object> getSession(String username) {
        commandLogger.startCapture();
        String sessionKey = SESSION_PREFIX + username;
        Map<Object, Object> entries = redis.opsForHash().entries(sessionKey);
        commandLogger.log("UC2", "HGETALL", sessionKey, null,
                "HGETALL " + sessionKey,
                entries.isEmpty() ? "(empty list — session expired or missing)"
                        : entries.size() + " fields returned");
        if (entries.isEmpty()) {
            commandLogger.getCaptured();
            return null;
        }

        Long ttl = redis.getExpire(sessionKey, TimeUnit.SECONDS);

        Map<String, Object> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v));
        result.put("sessionKey", sessionKey);
        result.put("ttl", ttl != null ? ttl : -1);
        result.put("redisCommands", commandLogger.getCaptured());
        return result;
    }

    /**
     * Get remaining TTL for a session.
     */
    public long getSessionTtl(String username) {
        String sessionKey = SESSION_PREFIX + username;
        Long ttl = redis.getExpire(sessionKey, TimeUnit.SECONDS);
        return ttl != null ? ttl : -2; // -2 means key does not exist
    }

    /**
     * Logout — delete session and token from Redis.
     */
    public Map<String, Object> logout(String username) {
        commandLogger.startCapture();
        String sessionKey = SESSION_PREFIX + username;

        // Get token before deleting session
        Object token = redis.opsForHash().get(sessionKey, "token");

        // DEL — remove session hash
        Boolean deleted = redis.delete(sessionKey);
        commandLogger.log("UC2", "DEL", sessionKey, null,
                "DEL " + sessionKey,
                "(integer) " + (Boolean.TRUE.equals(deleted) ? "1 (deleted)" : "0 (not found)"));

        // DEL — remove auth token
        if (token != null) {
            String tokenKey = TOKEN_PREFIX + token.toString();
            Boolean tokDel = redis.delete(tokenKey);
            commandLogger.log("UC2", "DEL", tokenKey, null,
                    "DEL " + tokenKey,
                    "(integer) " + (Boolean.TRUE.equals(tokDel) ? "1 (deleted)" : "0 (not found)"));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("deleted", Boolean.TRUE.equals(deleted));
        result.put("sessionKey", sessionKey);
        result.put("redisCommands", commandLogger.getCaptured());
        return result;
    }

    /**
     * Validate an auth token — returns the username if valid.
     */
    public String validateToken(String token) {
        String tokenKey = TOKEN_PREFIX + token;
        return redis.opsForValue().get(tokenKey);
    }
}
