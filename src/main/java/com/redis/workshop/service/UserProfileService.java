package com.redis.workshop.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * UC3: User Profile Storage.
 * Aggregates user profile from 3 mock databases into a single Redis Hash.
 * Demonstrates HSET (multiple fields), HGETALL, HINCRBY, EXPIRE, pipelining.
 */
@Service
public class UserProfileService {

    private static final long PROFILE_TTL_SECONDS = 600; // 10 minutes
    private static final String PROFILE_PREFIX = "workshop:profile:user:";

    // Mock "Accounts DB"
    private static final Map<String, Map<String, String>> ACCOUNTS_DB = Map.of(
            "U1001", Map.of("accountNumber", "ES76-0128-0001-23-0100012345",
                    "balance", "12450.75", "accountType", "Premium Savings"),
            "U1002", Map.of("accountNumber", "ES91-0128-0002-45-0200067890",
                    "balance", "85200.00", "accountType", "Business Current"),
            "U1003", Map.of("accountNumber", "ES44-0128-0003-67-0300011111",
                    "balance", "3200.50", "accountType", "Standard Current")
    );

    // Mock "Activity DB"
    private static final Map<String, Map<String, String>> ACTIVITY_DB = Map.of(
            "U1001", Map.of("lastLogin", "2024-04-12T09:15:00Z",
                    "recentTxCount", "14", "loginCount30d", "22"),
            "U1002", Map.of("lastLogin", "2024-04-13T16:42:00Z",
                    "recentTxCount", "47", "loginCount30d", "35"),
            "U1003", Map.of("lastLogin", "2024-04-10T11:30:00Z",
                    "recentTxCount", "5", "loginCount30d", "8")
    );

    // Mock "Preferences DB"
    private static final Map<String, Map<String, String>> PREFERENCES_DB = Map.of(
            "U1001", Map.of("language", "es", "notifications", "email",
                    "theme", "light", "fullName", "Ana García López"),
            "U1002", Map.of("language", "en", "notifications", "sms,email",
                    "theme", "dark", "fullName", "Carlos Ruiz Fernández"),
            "U1003", Map.of("language", "es", "notifications", "push",
                    "theme", "light", "fullName", "María López Torres")
    );

    private final StringRedisTemplate redis;

    public UserProfileService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Load profile by aggregating from 3 mock DBs into Redis Hash. */
    public Map<String, Object> loadProfile(String userId) {
        if (!ACCOUNTS_DB.containsKey(userId)) return null;

        String key = PROFILE_PREFIX + userId;

        // Aggregate from all 3 "databases"
        Map<String, String> profileData = new HashMap<>();
        profileData.put("userId", userId);

        // From Accounts DB
        Map<String, String> account = ACCOUNTS_DB.get(userId);
        account.forEach((k, v) -> profileData.put("account_" + k, v));

        // From Activity DB
        Map<String, String> activity = ACTIVITY_DB.get(userId);
        activity.forEach((k, v) -> profileData.put("activity_" + k, v));

        // From Preferences DB
        Map<String, String> prefs = PREFERENCES_DB.get(userId);
        prefs.forEach((k, v) -> profileData.put("pref_" + k, v));

        profileData.put("loadedAt", String.valueOf(System.currentTimeMillis()));

        // HSET — store all fields in one call
        redis.opsForHash().putAll(key, profileData);
        // EXPIRE — set TTL
        redis.expire(key, PROFILE_TTL_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> result = new HashMap<>(profileData);
        result.put("redisKey", key);
        result.put("ttl", PROFILE_TTL_SECONDS);
        result.put("fieldCount", profileData.size());
        result.put("sources", List.of("Accounts DB", "Activity DB", "Preferences DB"));
        return result;
    }

    /** Get profile from Redis. */
    public Map<String, Object> getProfile(String userId) {
        String key = PROFILE_PREFIX + userId;
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) return null;

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        Map<String, Object> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v));
        result.put("redisKey", key);
        result.put("ttl", ttl != null ? ttl : -1);
        return result;
    }

    /** Update specific profile fields. */
    public Map<String, Object> updateProfile(String userId, Map<String, String> updates) {
        String key = PROFILE_PREFIX + userId;
        if (Boolean.FALSE.equals(redis.hasKey(key))) return null;

        // HSET — update specific fields
        updates.forEach((field, value) ->
                redis.opsForHash().put(key, field, value));

        return getProfile(userId);
    }

    /** Simulate syncing back to mock DBs. */
    public Map<String, Object> syncProfile(String userId) {
        Map<String, Object> profile = getProfile(userId);
        if (profile == null) return null;

        return Map.of(
                "userId", userId,
                "synced", true,
                "message", "Profile synced back to Accounts DB, Activity DB, Preferences DB",
                "timestamp", String.valueOf(System.currentTimeMillis())
        );
    }

    /** List available users for the demo selector. */
    public List<Map<String, String>> listUsers() {
        return List.of(
                Map.of("userId", "U1001", "name", "Ana García López", "segment", "Premium"),
                Map.of("userId", "U1002", "name", "Carlos Ruiz Fernández", "segment", "Business"),
                Map.of("userId", "U1003", "name", "María López Torres", "segment", "Standard")
        );
    }
}
