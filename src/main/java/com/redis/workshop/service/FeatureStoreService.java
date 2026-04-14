package com.redis.workshop.service;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@DependsOn("startupCleanup")
public class FeatureStoreService {

    private static final String FEATURE_PREFIX = "workshop:features:client:";

    private final StringRedisTemplate redis;

    public FeatureStoreService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // Mock client profiles
    private static final Map<String, Map<String, String>> CLIENTS = new LinkedHashMap<>();
    static {
        CLIENTS.put("C1001", Map.of("name", "María García", "segment", "Premium", "country", "ES"));
        CLIENTS.put("C1002", Map.of("name", "John Smith", "segment", "Business", "country", "UK"));
        CLIENTS.put("C1003", Map.of("name", "Suspicious User", "segment", "Standard", "country", "RU"));
    }

    @PostConstruct
    public void loadInitialFeatures() {
        // Client A: María García — low risk, normal pattern
        storeFeatures("C1001", Map.of(
                "tx_count_1h", "2",
                "tx_count_24h", "5",
                "tx_amount_avg_24h", "120.50",
                "tx_amount_max_24h", "250.00",
                "distinct_countries_7d", "1",
                "distinct_devices_30d", "2",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(1800).toEpochMilli()),
                "risk_score", "12"
        ));

        // Client B: John Smith — medium risk, moderate activity
        storeFeatures("C1002", Map.of(
                "tx_count_1h", "5",
                "tx_count_24h", "18",
                "tx_amount_avg_24h", "450.75",
                "tx_amount_max_24h", "1200.00",
                "distinct_countries_7d", "3",
                "distinct_devices_30d", "4",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(600).toEpochMilli()),
                "risk_score", "47"
        ));

        // Client C: Suspicious User — high risk, high velocity + multiple countries
        storeFeatures("C1003", Map.of(
                "tx_count_1h", "15",
                "tx_count_24h", "42",
                "tx_amount_avg_24h", "2300.00",
                "tx_amount_max_24h", "9500.00",
                "distinct_countries_7d", "8",
                "distinct_devices_30d", "12",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(120).toEpochMilli()),
                "risk_score", "89"
        ));
    }

    private void storeFeatures(String clientId, Map<String, String> features) {
        String key = FEATURE_PREFIX + clientId;
        redis.opsForHash().putAll(key, features);
    }

    /** List all mock clients with their metadata. */
    public List<Map<String, String>> listClients() {
        List<Map<String, String>> result = new ArrayList<>();
        CLIENTS.forEach((id, meta) -> {
            Map<String, String> client = new HashMap<>(meta);
            client.put("clientId", id);
            result.add(client);
        });
        return result;
    }

    /** Get all features for a client using HGETALL. */
    public Map<String, Object> getFeatures(String clientId) {
        String key = FEATURE_PREFIX + clientId;
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientId", clientId);
        Map<String, String> meta = CLIENTS.get(clientId);
        if (meta != null) {
            result.put("clientName", meta.get("name"));
            result.put("segment", meta.get("segment"));
            result.put("country", meta.get("country"));
        }
        Map<String, String> features = new LinkedHashMap<>();
        entries.forEach((k, v) -> features.put(k.toString(), v.toString()));
        result.put("features", features);
        result.put("redisKey", key);
        return result;
    }

    /** Simulate a transaction: update features atomically using HINCRBY + HSET. */
    public Map<String, Object> simulateTransaction(String clientId, double amount, String country) {
        String key = FEATURE_PREFIX + clientId;

        // HINCRBY — increment transaction counters
        redis.opsForHash().increment(key, "tx_count_1h", 1);
        redis.opsForHash().increment(key, "tx_count_24h", 1);

        // HSET — update last transaction timestamp
        String now = String.valueOf(System.currentTimeMillis());
        redis.opsForHash().put(key, "last_tx_timestamp", now);

        // Recompute avg and max from current values
        Map<Object, Object> current = redis.opsForHash().entries(key);
        int txCount24h = Integer.parseInt(current.getOrDefault("tx_count_24h", "1").toString());
        double currentAvg = Double.parseDouble(current.getOrDefault("tx_amount_avg_24h", "0").toString());
        double currentMax = Double.parseDouble(current.getOrDefault("tx_amount_max_24h", "0").toString());

        // Running average approximation
        double newAvg = ((currentAvg * (txCount24h - 1)) + amount) / txCount24h;
        double newMax = Math.max(currentMax, amount);

        redis.opsForHash().put(key, "tx_amount_avg_24h", String.format("%.2f", newAvg));
        redis.opsForHash().put(key, "tx_amount_max_24h", String.format("%.2f", newMax));

        // Update distinct countries (simplified: increment if different from base)
        String baseCountry = CLIENTS.containsKey(clientId) ? CLIENTS.get(clientId).get("country") : "ES";
        if (!country.equalsIgnoreCase(baseCountry)) {
            redis.opsForHash().increment(key, "distinct_countries_7d", 1);
        }

        // Recompute risk score
        Map<Object, Object> updated = redis.opsForHash().entries(key);
        int riskScore = computeRiskScore(updated);
        redis.opsForHash().put(key, "risk_score", String.valueOf(riskScore));

        // Return the operation details for the UI
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientId", clientId);
        result.put("amount", amount);
        result.put("country", country);
        result.put("timestamp", now);
        result.put("riskScore", riskScore);
        result.put("redisCommands", List.of(
                "HINCRBY " + key + " tx_count_1h 1",
                "HINCRBY " + key + " tx_count_24h 1",
                "HSET " + key + " last_tx_timestamp " + now,
                "HSET " + key + " tx_amount_avg_24h " + String.format("%.2f", newAvg),
                "HSET " + key + " tx_amount_max_24h " + String.format("%.2f", newMax),
                "HSET " + key + " risk_score " + riskScore
        ));
        return result;
    }

    /**
     * Compute a risk score (0-100) based on feature values.
     * Simple heuristic for demo purposes.
     */
    private int computeRiskScore(Map<Object, Object> features) {
        int score = 0;

        int txCount1h = parseInt(features, "tx_count_1h");
        int txCount24h = parseInt(features, "tx_count_24h");
        double avgAmount = parseDouble(features, "tx_amount_avg_24h");
        double maxAmount = parseDouble(features, "tx_amount_max_24h");
        int countries = parseInt(features, "distinct_countries_7d");
        int devices = parseInt(features, "distinct_devices_30d");

        // High velocity in last hour
        if (txCount1h > 10) score += 25;
        else if (txCount1h > 5) score += 15;
        else if (txCount1h > 3) score += 5;

        // High 24h volume
        if (txCount24h > 30) score += 20;
        else if (txCount24h > 15) score += 10;
        else if (txCount24h > 8) score += 5;

        // Large average amounts
        if (avgAmount > 2000) score += 15;
        else if (avgAmount > 500) score += 8;

        // Very large max transaction
        if (maxAmount > 5000) score += 15;
        else if (maxAmount > 1000) score += 8;

        // Multiple countries
        if (countries > 5) score += 20;
        else if (countries > 3) score += 10;
        else if (countries > 1) score += 5;

        // Multiple devices
        if (devices > 8) score += 5;
        else if (devices > 4) score += 3;

        return Math.min(score, 100);
    }

    private int parseInt(Map<Object, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return 0;
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private double parseDouble(Map<Object, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return 0.0;
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
