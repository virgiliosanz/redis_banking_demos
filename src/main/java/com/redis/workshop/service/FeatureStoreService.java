package com.redis.workshop.service;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@DependsOn("startupCleanup")
public class FeatureStoreService {

    private static final String FEATURE_PREFIX = "uc7:";
    private static final String FEATURE_SET_V1 = "feature_set_v1";
    private static final String FEATURE_SET_V2 = "feature_set_v2";
    private static final String DEFAULT_FEATURE_SET = FEATURE_SET_V2;
    private static final double SIMULATED_DATABASE_FETCH_MS = 150.0;
    private static final List<String> AVAILABLE_FEATURE_SETS = List.of(FEATURE_SET_V1, FEATURE_SET_V2);

    private static final Map<String, Map<String, String>> CLIENTS = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> FEATURE_SET_V1_DATA = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> FEATURE_SET_V2_DATA = new LinkedHashMap<>();

    static {
        CLIENTS.put("C1001", Map.of("name", "María García", "segment", "Premium", "country", "ES"));
        CLIENTS.put("C1002", Map.of("name", "John Smith", "segment", "Business", "country", "UK"));
        CLIENTS.put("C1003", Map.of("name", "Suspicious User", "segment", "Standard", "country", "RU"));

        FEATURE_SET_V1_DATA.put("C1001", featureMap(
                "tx_count_1h", "2",
                "tx_count_24h", "5",
                "tx_amount_avg_24h", "120.50",
                "tx_amount_max_24h", "250.00",
                "distinct_countries_7d", "1",
                "distinct_devices_30d", "2",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(1800).toEpochMilli()),
                "risk_score", "12"
        ));
        FEATURE_SET_V1_DATA.put("C1002", featureMap(
                "tx_count_1h", "5",
                "tx_count_24h", "18",
                "tx_amount_avg_24h", "450.75",
                "tx_amount_max_24h", "1200.00",
                "distinct_countries_7d", "3",
                "distinct_devices_30d", "4",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(600).toEpochMilli()),
                "risk_score", "47"
        ));
        FEATURE_SET_V1_DATA.put("C1003", featureMap(
                "tx_count_1h", "15",
                "tx_count_24h", "42",
                "tx_amount_avg_24h", "2300.00",
                "tx_amount_max_24h", "9500.00",
                "distinct_countries_7d", "8",
                "distinct_devices_30d", "12",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(120).toEpochMilli()),
                "risk_score", "89"
        ));

        FEATURE_SET_V2_DATA.put("C1001", featureMap(
                "batch_income_monthly", "6200",
                "batch_dti_ratio", "0.21",
                "batch_payment_ratio_90d", "0.99",
                "batch_delinquency_count_12m", "0",
                "batch_credit_utilization", "0.28",
                "realtime_tx_count_1h", "2",
                "realtime_tx_count_24h", "5",
                "realtime_tx_amount_avg_24h", "120.50",
                "realtime_tx_amount_max_24h", "250.00",
                "realtime_distinct_countries_7d", "1",
                "realtime_distinct_devices_30d", "2",
                "realtime_seconds_since_last_tx", "1800",
                "realtime_risk_score", "10",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(1800).toEpochMilli())
        ));
        FEATURE_SET_V2_DATA.put("C1002", featureMap(
                "batch_income_monthly", "4100",
                "batch_dti_ratio", "0.39",
                "batch_payment_ratio_90d", "0.93",
                "batch_delinquency_count_12m", "1",
                "batch_credit_utilization", "0.57",
                "realtime_tx_count_1h", "5",
                "realtime_tx_count_24h", "18",
                "realtime_tx_amount_avg_24h", "450.75",
                "realtime_tx_amount_max_24h", "1200.00",
                "realtime_distinct_countries_7d", "3",
                "realtime_distinct_devices_30d", "4",
                "realtime_seconds_since_last_tx", "600",
                "realtime_risk_score", "38",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(600).toEpochMilli())
        ));
        FEATURE_SET_V2_DATA.put("C1003", featureMap(
                "batch_income_monthly", "1900",
                "batch_dti_ratio", "0.68",
                "batch_payment_ratio_90d", "0.61",
                "batch_delinquency_count_12m", "4",
                "batch_credit_utilization", "0.92",
                "realtime_tx_count_1h", "15",
                "realtime_tx_count_24h", "42",
                "realtime_tx_amount_avg_24h", "2300.00",
                "realtime_tx_amount_max_24h", "9500.00",
                "realtime_distinct_countries_7d", "8",
                "realtime_distinct_devices_30d", "12",
                "realtime_seconds_since_last_tx", "120",
                "realtime_risk_score", "91",
                "last_tx_timestamp", String.valueOf(Instant.now().minusSeconds(120).toEpochMilli())
        ));
    }

    private final StringRedisTemplate redis;

    public FeatureStoreService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void loadInitialFeatures() {
        FEATURE_SET_V1_DATA.forEach((clientId, features) -> storeFeatures(clientId, FEATURE_SET_V1, features));
        FEATURE_SET_V2_DATA.forEach((clientId, features) -> storeFeatures(clientId, FEATURE_SET_V2, features));
    }

    /** List all mock clients with their metadata. */
    public List<Map<String, String>> listClients() {
        List<Map<String, String>> result = new ArrayList<>();
        CLIENTS.forEach((id, meta) -> {
            Map<String, String> client = new LinkedHashMap<>(meta);
            client.put("clientId", id);
            result.add(client);
        });
        return result;
    }

    /** Get all features for a client using HGETALL. */
    public Map<String, Object> getFeatures(String clientId) {
        return getFeatures(clientId, DEFAULT_FEATURE_SET);
    }

    public Map<String, Object> getFeatures(String clientId, String requestedFeatureSet) {
        String featureSet = normalizeFeatureSet(requestedFeatureSet);
        Map<String, String> features = getFeatureEntries(clientId, featureSet);
        if (features.isEmpty()) {
            return null;
        }
        return buildFeatureResponse(clientId, featureSet, features);
    }

    /** Simulate online inference by fetching features from Redis and scoring with a mock model. */
    public Map<String, Object> runInference(String clientId, String requestedFeatureSet) {
        String featureSet = normalizeFeatureSet(requestedFeatureSet);

        long totalStart = System.nanoTime();
        long fetchStart = System.nanoTime();
        Map<String, String> features = getFeatureEntries(clientId, featureSet);
        long fetchNanos = System.nanoTime() - fetchStart;

        if (features.isEmpty()) {
            return null;
        }

        long modelStart = System.nanoTime();
        Map<String, Object> modelOutput = simulateCreditDecision(featureSet, features);
        long modelNanos = System.nanoTime() - modelStart;
        long totalNanos = System.nanoTime() - totalStart;

        double redisFetchMs = round(nanosToMs(fetchNanos), 3);
        double modelMs = round(nanosToMs(modelNanos), 3);
        double totalMs = round(nanosToMs(totalNanos), 3);

        Map<String, Object> response = buildFeatureResponse(clientId, featureSet, features);
        response.put("featuresFetched", features.size());
        response.put("modelName", FEATURE_SET_V1.equals(featureSet) ? "credit_policy_baseline_v1" : "credit_decision_v2_mock");
        response.putAll(modelOutput);

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("redisFeatureFetchMs", redisFetchMs);
        latency.put("modelInferenceMs", modelMs);
        latency.put("totalMs", totalMs);
        response.put("latency", latency);

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("redisFeatureFetchMs", redisFetchMs);
        comparison.put("simulatedDatabaseFetchMs", SIMULATED_DATABASE_FETCH_MS);
        comparison.put("savedMs", round(SIMULATED_DATABASE_FETCH_MS - redisFetchMs, 3));
        comparison.put("speedupX", round(SIMULATED_DATABASE_FETCH_MS / Math.max(redisFetchMs, 0.05), 1));
        response.put("comparison", comparison);

        return response;
    }

    /** Simulate a transaction: update real-time features using HINCRBY + HSET. */
    public Map<String, Object> simulateTransaction(String clientId, double amount, String country) {
        String now = String.valueOf(System.currentTimeMillis());

        updateFeatureSetV1(clientId, amount, country, now);
        updateFeatureSetV2(clientId, amount, country, now);

        Map<String, String> v2Features = getFeatureEntries(clientId, FEATURE_SET_V2);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientId", clientId);
        result.put("amount", round(amount, 2));
        result.put("country", country);
        result.put("timestamp", now);
        result.put("featureSetVersion", FEATURE_SET_V2);
        result.put("updatedFeatureSets", AVAILABLE_FEATURE_SETS);
        result.put("riskScore", parseInt(v2Features, "realtime_risk_score"));
        return result;
    }

    private void updateFeatureSetV1(String clientId, double amount, String country, String now) {
        String key = buildKey(clientId, FEATURE_SET_V1);

        redis.opsForHash().increment(key, "tx_count_1h", 1);
        redis.opsForHash().increment(key, "tx_count_24h", 1);
        redis.opsForHash().put(key, "last_tx_timestamp", now);

        Map<Object, Object> current = redis.opsForHash().entries(key);
        int txCount24h = Math.max(1, parseInt(current, "tx_count_24h"));
        double currentAvg = parseDouble(current, "tx_amount_avg_24h");
        double currentMax = parseDouble(current, "tx_amount_max_24h");

        double newAvg = ((currentAvg * Math.max(txCount24h - 1, 0)) + amount) / txCount24h;
        double newMax = Math.max(currentMax, amount);

        redis.opsForHash().put(key, "tx_amount_avg_24h", formatDecimal(newAvg));
        redis.opsForHash().put(key, "tx_amount_max_24h", formatDecimal(newMax));

        if (!country.equalsIgnoreCase(clientBaseCountry(clientId))) {
            redis.opsForHash().increment(key, "distinct_countries_7d", 1);
        }

        Map<Object, Object> updated = redis.opsForHash().entries(key);
        redis.opsForHash().put(key, "risk_score", String.valueOf(computeLegacyRiskScore(updated)));
    }

    private void updateFeatureSetV2(String clientId, double amount, String country, String now) {
        String key = buildKey(clientId, FEATURE_SET_V2);

        redis.opsForHash().increment(key, "realtime_tx_count_1h", 1);
        redis.opsForHash().increment(key, "realtime_tx_count_24h", 1);
        redis.opsForHash().put(key, "last_tx_timestamp", now);
        redis.opsForHash().put(key, "realtime_seconds_since_last_tx", "0");

        Map<Object, Object> current = redis.opsForHash().entries(key);
        int txCount24h = Math.max(1, parseInt(current, "realtime_tx_count_24h"));
        double currentAvg = parseDouble(current, "realtime_tx_amount_avg_24h");
        double currentMax = parseDouble(current, "realtime_tx_amount_max_24h");

        double newAvg = ((currentAvg * Math.max(txCount24h - 1, 0)) + amount) / txCount24h;
        double newMax = Math.max(currentMax, amount);

        redis.opsForHash().put(key, "realtime_tx_amount_avg_24h", formatDecimal(newAvg));
        redis.opsForHash().put(key, "realtime_tx_amount_max_24h", formatDecimal(newMax));

        if (!country.equalsIgnoreCase(clientBaseCountry(clientId))) {
            redis.opsForHash().increment(key, "realtime_distinct_countries_7d", 1);
        }

        Map<Object, Object> updated = redis.opsForHash().entries(key);
        redis.opsForHash().put(key, "realtime_risk_score", String.valueOf(computeRealtimeRiskScore(updated)));
    }

    private Map<String, Object> simulateCreditDecision(String featureSet, Map<String, String> features) {
        double probabilityScore;
        List<String> signals = new ArrayList<>();

        if (FEATURE_SET_V1.equals(featureSet)) {
            int riskScore = parseInt(features, "risk_score");
            int countries = parseInt(features, "distinct_countries_7d");
            int txCount1h = parseInt(features, "tx_count_1h");
            probabilityScore = clamp(0.96 - (riskScore / 120.0) - (countries * 0.018) - (txCount1h * 0.01), 0.03, 0.97);

            if (riskScore < 20) signals.add("Low legacy risk score from the Redis feature hash");
            if (riskScore >= 45) signals.add("Baseline feature set shows elevated risk heuristics");
            if (countries > 2) signals.add("Cross-border activity reduced approval probability");
            if (signals.isEmpty()) signals.add("Stable baseline behaviour kept the score near auto-approve");
        } else {
            double incomeMonthly = parseDouble(features, "batch_income_monthly");
            double dtiRatio = parseDouble(features, "batch_dti_ratio");
            double paymentRatio90d = parseDouble(features, "batch_payment_ratio_90d");
            double delinquency12m = parseDouble(features, "batch_delinquency_count_12m");
            double utilization = parseDouble(features, "batch_credit_utilization");
            double txCount1h = parseDouble(features, "realtime_tx_count_1h");
            double txRisk = parseDouble(features, "realtime_risk_score");
            double countries = parseDouble(features, "realtime_distinct_countries_7d");

            probabilityScore = 0.56
                    + normalize(incomeMonthly, 0, 8000) * 0.15
                    + paymentRatio90d * 0.18
                    - dtiRatio * 0.20
                    - utilization * 0.15
                    - normalize(delinquency12m, 0, 4) * 0.10
                    - normalize(txCount1h, 0, 15) * 0.08
                    - normalize(txRisk, 0, 100) * 0.12
                    - normalize(countries, 1, 8) * 0.05;
            probabilityScore = clamp(probabilityScore, 0.02, 0.98);

            if (incomeMonthly >= 5000) signals.add("Strong monthly income batch feature boosted affordability");
            if (paymentRatio90d >= 0.95) signals.add("High 90-day payment ratio supported the model score");
            if (dtiRatio >= 0.50 || utilization >= 0.80) signals.add("Debt or utilization pressure pushed the case toward deny/review");
            if (txRisk >= 70 || countries >= 5) signals.add("Real-time behavioural risk features increased model caution");
            if (signals.isEmpty()) signals.add("Balanced batch and real-time features produced a borderline outcome");
        }

        String decision;
        if (probabilityScore >= 0.72) {
            decision = "APPROVE";
        } else if (probabilityScore <= 0.40) {
            decision = "DENY";
        } else {
            decision = "REVIEW";
        }

        double confidenceScore = clamp(0.55 + Math.abs(probabilityScore - 0.56) * 0.9, 0.55, 0.99);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decision", decision);
        result.put("probabilityScore", round(probabilityScore, 3));
        result.put("confidenceScore", round(confidenceScore, 3));
        result.put("signals", signals);
        return result;
    }

    private Map<String, Object> buildFeatureResponse(String clientId, String featureSet, Map<String, String> features) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientId", clientId);
        Map<String, String> meta = CLIENTS.get(clientId);
        if (meta != null) {
            result.put("clientName", meta.get("name"));
            result.put("segment", meta.get("segment"));
            result.put("country", meta.get("country"));
        }
        result.put("featureSetVersion", featureSet);
        result.put("availableFeatureSets", AVAILABLE_FEATURE_SETS);
        result.put("redisKey", buildKey(clientId, featureSet));
        result.put("features", features);
        result.put("featureGroups", buildFeatureGroups(featureSet, features));
        return result;
    }

    private Map<String, Map<String, String>> buildFeatureGroups(String featureSet, Map<String, String> features) {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        if (FEATURE_SET_V2.equals(featureSet)) {
            groups.put("batch", filterByPrefix(features, "batch_"));
            groups.put("realtime", filterByPrefix(features, "realtime_"));
            Map<String, String> metadata = new LinkedHashMap<>();
            if (features.containsKey("last_tx_timestamp")) {
                metadata.put("last_tx_timestamp", features.get("last_tx_timestamp"));
            }
            groups.put("metadata", metadata);
        } else {
            groups.put("baseline", new LinkedHashMap<>(features));
        }
        return groups;
    }

    private Map<String, String> filterByPrefix(Map<String, String> features, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        features.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                result.put(key, value);
            }
        });
        return result;
    }

    private Map<String, String> getFeatureEntries(String clientId, String featureSet) {
        String key = buildKey(clientId, featureSet);
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        Map<String, String> features = new LinkedHashMap<>();
        entries.forEach((k, v) -> features.put(k.toString(), v.toString()));
        return features;
    }

    private void storeFeatures(String clientId, String featureSet, Map<String, String> features) {
        redis.opsForHash().putAll(buildKey(clientId, featureSet), features);
    }

    private String buildKey(String clientId, String featureSet) {
        return FEATURE_PREFIX + featureSet + ":client:" + clientId;
    }

    private String normalizeFeatureSet(String requestedFeatureSet) {
        if (requestedFeatureSet == null || requestedFeatureSet.isBlank()) {
            return DEFAULT_FEATURE_SET;
        }
        String value = requestedFeatureSet.trim().toLowerCase(Locale.ROOT);
        if ("v1".equals(value) || FEATURE_SET_V1.equals(value)) {
            return FEATURE_SET_V1;
        }
        if ("v2".equals(value) || FEATURE_SET_V2.equals(value)) {
            return FEATURE_SET_V2;
        }
        return DEFAULT_FEATURE_SET;
    }

    private int computeLegacyRiskScore(Map<?, ?> features) {
        int score = 0;

        int txCount1h = parseInt(features, "tx_count_1h");
        int txCount24h = parseInt(features, "tx_count_24h");
        double avgAmount = parseDouble(features, "tx_amount_avg_24h");
        double maxAmount = parseDouble(features, "tx_amount_max_24h");
        int countries = parseInt(features, "distinct_countries_7d");
        int devices = parseInt(features, "distinct_devices_30d");

        if (txCount1h > 10) score += 25;
        else if (txCount1h > 5) score += 15;
        else if (txCount1h > 3) score += 5;

        if (txCount24h > 30) score += 20;
        else if (txCount24h > 15) score += 10;
        else if (txCount24h > 8) score += 5;

        if (avgAmount > 2000) score += 15;
        else if (avgAmount > 500) score += 8;

        if (maxAmount > 5000) score += 15;
        else if (maxAmount > 1000) score += 8;

        if (countries > 5) score += 20;
        else if (countries > 3) score += 10;
        else if (countries > 1) score += 5;

        if (devices > 8) score += 5;
        else if (devices > 4) score += 3;

        return Math.min(score, 100);
    }

    private int computeRealtimeRiskScore(Map<?, ?> features) {
        double score = normalize(parseDouble(features, "realtime_tx_count_1h"), 0, 15) * 26
                + normalize(parseDouble(features, "realtime_tx_count_24h"), 0, 42) * 16
                + normalize(parseDouble(features, "realtime_tx_amount_avg_24h"), 0, 2500) * 12
                + normalize(parseDouble(features, "realtime_tx_amount_max_24h"), 0, 10000) * 16
                + normalize(parseDouble(features, "realtime_distinct_countries_7d"), 1, 8) * 18
                + normalize(parseDouble(features, "realtime_distinct_devices_30d"), 1, 12) * 8
                + normalize(3600 - Math.min(parseDouble(features, "realtime_seconds_since_last_tx"), 3600), 0, 3600) * 4;
        return (int) Math.round(clamp(score, 0, 100));
    }

    private String clientBaseCountry(String clientId) {
        Map<String, String> meta = CLIENTS.get(clientId);
        return meta != null ? meta.getOrDefault("country", "ES") : "ES";
    }

    private static Map<String, String> featureMap(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    private int parseInt(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            return 0;
        }
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDouble(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double normalize(double value, double min, double max) {
        if (max <= min) {
            return 0.0;
        }
        return clamp((value - min) / (max - min), 0.0, 1.0);
    }

    private double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private double round(double value, int decimals) {
        double multiplier = Math.pow(10, decimals);
        return Math.round(value * multiplier) / multiplier;
    }

    private String formatDecimal(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
