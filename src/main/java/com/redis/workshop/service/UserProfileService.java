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
    private static final String PROFILE_PREFIX = "uc3:user:";

    // Mock demo datasets for the selector and hash aggregation flow.
    private static final Map<String, Map<String, String>> ACCOUNTS_DB = buildAccountsDb();
    private static final Map<String, Map<String, String>> ACTIVITY_DB = buildActivityDb();
    private static final Map<String, Map<String, String>> PREFERENCES_DB = buildPreferencesDb();

    private final StringRedisTemplate redis;

    public UserProfileService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Load profile by aggregating from 3 mock DBs into Redis Hash. */
    public Map<String, Object> loadProfile(String userId) {
        if (!ACCOUNTS_DB.containsKey(userId)) {
            return null;
        }

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
        if (entries.isEmpty()) {
            return null;
        }

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
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            return null;
        }

        // HSET — update specific fields
        updates.forEach((field, value) -> redis.opsForHash().put(key, field, value));

        Map<Object, Object> entries = redis.opsForHash().entries(key);
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        Map<String, Object> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v));
        result.put("redisKey", key);
        result.put("ttl", ttl != null ? ttl : -1);
        return result;
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
        List<Map<String, String>> users = new ArrayList<>();
        ACCOUNTS_DB.forEach((userId, account) -> {
            Map<String, String> prefs = PREFERENCES_DB.getOrDefault(userId, Map.of());
            Map<String, String> user = new LinkedHashMap<>();
            user.put("userId", userId);
            user.put("name", prefs.getOrDefault("fullName", userId));
            user.put("segment", account.getOrDefault("segment", "Basic"));
            user.put("country", account.getOrDefault("country", "ES"));
            user.put("accountType", account.getOrDefault("accountType", "Checking"));
            users.add(user);
        });
        return users;
    }

    private static Map<String, Map<String, String>> buildAccountsDb() {
        Map<String, Map<String, String>> db = new LinkedHashMap<>();
        db.put("U1001", account("DEMO-ES-1001-001", "12450.75", "Premium Savings", "Premium", "ES"));
        db.put("U1002", account("DEMO-UK-1002-002", "85200.00", "Business Current", "Business", "UK"));
        db.put("U1003", account("DEMO-PT-1003-003", "3200.50", "Everyday Checking", "Basic", "PT"));
        db.put("U1004", account("DEMO-FR-1004-004", "245000.00", "Private Wealth Reserve", "Private Banking", "FR"));
        db.put("U1005", account("DEMO-DE-1005-005", "48650.90", "Mortgage Offset", "Premium", "DE"));
        db.put("U1006", account("DEMO-PT-1006-006", "1450.25", "Student Account", "Student", "PT"));
        db.put("U1007", account("DEMO-IT-1007-007", "17890.40", "Multi-Currency Account", "Premium", "IT"));
        db.put("U1008", account("DEMO-IE-1008-008", "5380.10", "Family Current", "Basic", "IE"));
        db.put("U1009", account("DEMO-NL-1009-009", "132500.00", "Merchant Settlement", "Business", "NL"));
        db.put("U1010", account("DEMO-ES-1010-010", "27440.80", "Green Savings", "Premium", "ES"));
        db.put("U1011", account("DEMO-FR-1011-011", "6980.35", "Salary Account", "Basic", "FR"));
        db.put("U1012", account("DEMO-CH-1012-012", "412000.00", "Global Private Portfolio", "Private Banking", "CH"));
        db.put("U1013", account("DEMO-SE-1013-013", "23110.55", "Wealth Builder Checking", "Premium", "SE"));
        db.put("U1014", account("DEMO-PT-1014-014", "90550.60", "SME Working Capital", "Business", "PT"));
        db.put("U1015", account("DEMO-IT-1015-015", "8940.45", "Joint Current", "Basic", "IT"));
        db.put("U1016", account("DEMO-DK-1016-016", "22110.00", "Travel Rewards Account", "Premium", "DK"));
        return Collections.unmodifiableMap(db);
    }

    private static Map<String, Map<String, String>> buildActivityDb() {
        Map<String, Map<String, String>> db = new LinkedHashMap<>();
        db.put("U1001", activity("2026-05-12T09:15:00Z", "14", "22"));
        db.put("U1002", activity("2026-05-13T16:42:00Z", "47", "35"));
        db.put("U1003", activity("2026-05-10T11:30:00Z", "5", "8"));
        db.put("U1004", activity("2026-05-15T07:45:00Z", "28", "19"));
        db.put("U1005", activity("2026-05-14T18:05:00Z", "21", "27"));
        db.put("U1006", activity("2026-05-11T21:10:00Z", "9", "41"));
        db.put("U1007", activity("2026-05-15T06:18:00Z", "32", "24"));
        db.put("U1008", activity("2026-05-09T14:28:00Z", "11", "16"));
        db.put("U1009", activity("2026-05-13T05:55:00Z", "58", "44"));
        db.put("U1010", activity("2026-05-15T08:12:00Z", "19", "29"));
        db.put("U1011", activity("2026-05-08T19:33:00Z", "7", "12"));
        db.put("U1012", activity("2026-05-14T12:47:00Z", "36", "18"));
        db.put("U1013", activity("2026-05-12T22:14:00Z", "24", "31"));
        db.put("U1014", activity("2026-05-15T04:40:00Z", "41", "37"));
        db.put("U1015", activity("2026-05-11T10:05:00Z", "13", "15"));
        db.put("U1016", activity("2026-05-13T20:52:00Z", "26", "21"));
        return Collections.unmodifiableMap(db);
    }

    private static Map<String, Map<String, String>> buildPreferencesDb() {
        Map<String, Map<String, String>> db = new LinkedHashMap<>();
        db.put("U1001", preferences("es", "email", "light", "Lucía Navarro Vega"));
        db.put("U1002", preferences("en", "sms,email", "dark", "Owen Hartwell"));
        db.put("U1003", preferences("pt", "push", "light", "Sofia Marin Costa"));
        db.put("U1004", preferences("fr", "email,advisor", "dark", "Élise Moreau"));
        db.put("U1005", preferences("de", "sms,push", "dark", "Nico Weber"));
        db.put("U1006", preferences("pt", "push,email", "light", "Inês Duarte"));
        db.put("U1007", preferences("it", "email", "dark", "Matteo Rinaldi"));
        db.put("U1008", preferences("en", "email", "light", "Aoife Brennan"));
        db.put("U1009", preferences("nl", "sms,email", "dark", "Hugo van Dijk"));
        db.put("U1010", preferences("es", "push,email", "light", "Clara Vidal Serra"));
        db.put("U1011", preferences("fr", "email", "light", "Léa Mercier"));
        db.put("U1012", preferences("de", "advisor,email", "dark", "Tobias Keller"));
        db.put("U1013", preferences("sv", "push", "light", "Freja Nordholm"));
        db.put("U1014", preferences("pt", "sms,email", "dark", "Diogo Matos"));
        db.put("U1015", preferences("it", "email,push", "light", "Giulia Ferraro"));
        db.put("U1016", preferences("en", "sms", "dark", "Soren Nygaard"));
        return Collections.unmodifiableMap(db);
    }

    private static Map<String, String> account(String accountNumber, String balance, String accountType,
                                               String segment, String country) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("accountNumber", accountNumber);
        map.put("balance", balance);
        map.put("accountType", accountType);
        map.put("segment", segment);
        map.put("country", country);
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> activity(String lastLogin, String recentTxCount, String loginCount30d) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("lastLogin", lastLogin);
        map.put("recentTxCount", recentTxCount);
        map.put("loginCount30d", loginCount30d);
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> preferences(String language, String notifications, String theme,
                                                   String fullName) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("language", language);
        map.put("notifications", notifications);
        map.put("theme", theme);
        map.put("fullName", fullName);
        return Collections.unmodifiableMap(map);
    }
}
