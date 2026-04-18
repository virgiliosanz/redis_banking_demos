package com.redis.workshop.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * UC1: Authentication Token Store.
 * Generates JWT-like tokens and stores them in Redis Hash with TTL.
 * Demonstrates HSET, HGET, HGETALL, DEL, EXPIRE.
 */
@Service
public class AuthTokenService {

    private static final long TOKEN_TTL_SECONDS = 300; // 5 minutes for demo
    private static final String TOKEN_PREFIX = "uc1:token:";

    // Mock users: username -> password
    private static final Map<String, String> MOCK_USERS = Map.of(
            "user1", "password1",
            "user2", "password2"
    );

    // Mock user info for token payload
    private static final Map<String, Map<String, String>> USER_INFO = Map.of(
            "user1", Map.of("fullName", "Ana García López", "email", "ana.garcia@banco.es",
                    "role", "Premium Client"),
            "user2", Map.of("fullName", "Carlos Ruiz Fernández", "email", "carlos.ruiz@banco.es",
                    "role", "Business Client")
    );

    private final StringRedisTemplate redis;

    public AuthTokenService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Authenticate user and create auth token stored as Redis Hash.
     */
    public Map<String, Object> login(String username, String password) {
        String expected = MOCK_USERS.get(username);
        if (expected == null || !expected.equals(password)) {
            return null;
        }

        // Generate token ID
        String tokenId = UUID.randomUUID().toString();
        String tokenKey = TOKEN_PREFIX + tokenId;

        // Build token data
        Map<String, String> info = USER_INFO.get(username);
        Map<String, String> tokenData = new HashMap<>();
        tokenData.put("tokenId", tokenId);
        tokenData.put("username", username);
        tokenData.put("fullName", info.get("fullName"));
        tokenData.put("email", info.get("email"));
        tokenData.put("role", info.get("role"));
        tokenData.put("issuedAt", String.valueOf(System.currentTimeMillis()));
        tokenData.put("ipAddress", "192.168.1." + (int) (Math.random() * 254 + 1));

        // HSET — store token as Redis Hash
        redis.opsForHash().putAll(tokenKey, tokenData);
        // EXPIRE — set TTL
        redis.expire(tokenKey, TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> result = new HashMap<>(tokenData);
        result.put("redisKey", tokenKey);
        result.put("ttl", TOKEN_TTL_SECONDS);
        return result;
    }

    /**
     * Validate token — returns token info if valid, null if expired/invalid.
     */
    public Map<String, Object> validateToken(String tokenId) {
        String tokenKey = TOKEN_PREFIX + tokenId;
        Map<Object, Object> entries = redis.opsForHash().entries(tokenKey);
        if (entries.isEmpty()) {
            return null;
        }

        Long ttl = redis.getExpire(tokenKey, TimeUnit.SECONDS);

        Map<String, Object> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v));
        result.put("valid", true);
        result.put("redisKey", tokenKey);
        result.put("ttl", ttl != null ? ttl : -1);
        return result;
    }

    /**
     * Logout — delete token from Redis.
     */
    public Map<String, Object> logout(String tokenId) {
        String tokenKey = TOKEN_PREFIX + tokenId;
        Boolean deleted = redis.delete(tokenKey);
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", Boolean.TRUE.equals(deleted));
        result.put("redisKey", tokenKey);
        return result;
    }


}
