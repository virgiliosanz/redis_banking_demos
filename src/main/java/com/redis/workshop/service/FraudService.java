package com.redis.workshop.service;

import com.redis.workshop.config.RedisScanHelper;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@DependsOn("startupCleanup")
public class FraudService {

    private static final String VELOCITY_KEY_PREFIX = "uc6:velocity:";
    private static final String STREAM_KEY = "uc6:stream:transactions";
    private static final long VELOCITY_WINDOW_SECONDS = 300; // 5-minute window
    private static final int VELOCITY_THRESHOLD_MEDIUM = 3;
    private static final int VELOCITY_THRESHOLD_HIGH = 5;
    private static final double AMOUNT_THRESHOLD_MEDIUM = 5000.0;
    private static final double AMOUNT_THRESHOLD_HIGH = 10000.0;

    // "Normal" countries for each demo card
    private static final Map<String, String> CARD_HOME_COUNTRY = Map.of(
            "4000-1234-5678-9010", "ES",
            "4000-2345-6789-0123", "ES",
            "4000-3456-7890-1234", "FR",
            "4000-4567-8901-2345", "DE"
    );

    private final StringRedisTemplate redis;

    public FraudService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Pre-load baseline transaction history so velocity checks have data.
     */
    @PostConstruct
    public void loadBaselineData() {
        long now = Instant.now().toEpochMilli();
        // Add 2 "normal" past transactions per card (spread over last 4 minutes)
        CARD_HOME_COUNTRY.forEach((card, country) -> {
            String key = VELOCITY_KEY_PREFIX + card;
            redis.opsForZSet().add(key, "baseline-1-" + card, now - 240_000);
            redis.opsForZSet().add(key, "baseline-2-" + card, now - 120_000);
        });
    }

    /**
     * Evaluate a transaction and return risk assessment.
     */
    public Map<String, Object> evaluateTransaction(String cardNumber, double amount,
                                                    String merchant, String country) {
        String txId = "tx-" + UUID.randomUUID().toString().substring(0, 8);
        long now = Instant.now().toEpochMilli();

        // 1. Velocity check — ZADD + ZRANGEBYSCORE
        String velocityKey = VELOCITY_KEY_PREFIX + cardNumber;
        redis.opsForZSet().add(velocityKey, txId, now);
        // Count transactions in the last N minutes
        long windowStart = now - (VELOCITY_WINDOW_SECONDS * 1000);
        Set<String> recentTxs = redis.opsForZSet().rangeByScore(velocityKey, windowStart, now);
        int velocityCount = recentTxs != null ? recentTxs.size() : 0;

        // Clean old entries outside window
        redis.opsForZSet().removeRangeByScore(velocityKey, 0, windowStart - 1);

        // 2. Amount check
        boolean highAmount = amount >= AMOUNT_THRESHOLD_HIGH;
        boolean mediumAmount = amount >= AMOUNT_THRESHOLD_MEDIUM;

        // 3. Geographic anomaly
        String homeCountry = CARD_HOME_COUNTRY.getOrDefault(cardNumber, "ES");
        boolean geoAnomaly = !homeCountry.equalsIgnoreCase(country);

        // 4. Calculate risk score (0-100)
        int riskScore = calculateRiskScore(velocityCount, amount, geoAnomaly);
        String riskLevel = getRiskLevel(riskScore);

        // Build risk factors
        List<String> factors = new ArrayList<>();
        if (velocityCount >= VELOCITY_THRESHOLD_HIGH) {
            factors.add("HIGH VELOCITY: " + velocityCount + " txs in " + (VELOCITY_WINDOW_SECONDS / 60) + " min");
        } else if (velocityCount >= VELOCITY_THRESHOLD_MEDIUM) {
            factors.add("Elevated velocity: " + velocityCount + " txs in " + (VELOCITY_WINDOW_SECONDS / 60) + " min");
        }
        if (highAmount) {
            factors.add("HIGH AMOUNT: €" + String.format("%.2f", amount));
        } else if (mediumAmount) {
            factors.add("Elevated amount: €" + String.format("%.2f", amount));
        }
        if (geoAnomaly) {
            factors.add("GEO ANOMALY: tx from " + country + ", home country is " + homeCountry);
        }
        if (factors.isEmpty()) {
            factors.add("All checks passed — normal transaction");
        }

        // 5. Log to Redis Stream — XADD
        Map<String, String> streamEntry = new LinkedHashMap<>();
        streamEntry.put("txId", txId);
        streamEntry.put("card", cardNumber);
        streamEntry.put("amount", String.format("%.2f", amount));
        streamEntry.put("merchant", merchant);
        streamEntry.put("country", country);
        streamEntry.put("riskScore", String.valueOf(riskScore));
        streamEntry.put("riskLevel", riskLevel);
        streamEntry.put("velocityCount", String.valueOf(velocityCount));
        streamEntry.put("geoAnomaly", String.valueOf(geoAnomaly));
        streamEntry.put("timestamp", Instant.now().toString());

        MapRecord<String, String, String> record = StreamRecords.string(streamEntry)
                .withStreamKey(STREAM_KEY);
        redis.opsForStream().add(record);

        // Trim stream to last 50 entries
        redis.opsForStream().trim(STREAM_KEY, 50);

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("txId", txId);
        result.put("cardNumber", cardNumber);
        result.put("amount", amount);
        result.put("merchant", merchant);
        result.put("country", country);
        result.put("riskScore", riskScore);
        result.put("riskLevel", riskLevel);
        result.put("velocityCount", velocityCount);
        result.put("geoAnomaly", geoAnomaly);
        result.put("factors", factors);
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    /**
     * Read recent entries from the fraud evaluation stream — XRANGE.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRecentStream(int count) {
        var records = redis.opsForStream().reverseRange(STREAM_KEY,
                org.springframework.data.domain.Range.unbounded(),
                org.springframework.data.redis.connection.Limit.limit().count(count));
        if (records == null) return Collections.emptyList();

        List<Map<String, Object>> entries = new ArrayList<>();
        for (var rec : records) {
            Map<String, Object> entry = new LinkedHashMap<>((Map) rec.getValue());
            entry.put("streamId", rec.getId().getValue());
            entries.add(entry);
        }
        return entries;
    }

    /**
     * Reset demo data: clear stream and velocity keys.
     */
    public void reset() {
        redis.delete(STREAM_KEY);
        Set<String> velocityKeys = RedisScanHelper.scanKeys(redis, VELOCITY_KEY_PREFIX + "*");
        if (!velocityKeys.isEmpty()) {
            redis.delete(velocityKeys);
        }
        loadBaselineData();
    }

    private int calculateRiskScore(int velocityCount, double amount, boolean geoAnomaly) {
        int score = 0;
        // Velocity component (0-40)
        if (velocityCount >= VELOCITY_THRESHOLD_HIGH) score += 40;
        else if (velocityCount >= VELOCITY_THRESHOLD_MEDIUM) score += 20;
        else if (velocityCount >= 2) score += 5;

        // Amount component (0-35)
        if (amount >= AMOUNT_THRESHOLD_HIGH) score += 35;
        else if (amount >= AMOUNT_THRESHOLD_MEDIUM) score += 20;
        else if (amount >= 2000) score += 8;

        // Geo component (0-25)
        if (geoAnomaly) score += 25;

        return Math.min(score, 100);
    }

    private String getRiskLevel(int score) {
        if (score >= 70) return "CRITICAL";
        if (score >= 45) return "HIGH";
        if (score >= 25) return "MEDIUM";
        return "LOW";
    }
}
