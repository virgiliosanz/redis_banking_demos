package com.redis.workshop.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Service
public class TransactionMonitorService {

    private final StringRedisTemplate redis;
    private ScheduledExecutorService simulator;
    private volatile boolean simulating = false;
    private final Random random = new Random();

    private static final String TS_COUNT = "workshop:txmonitor:ts:count";
    private static final String TS_AMOUNT = "workshop:txmonitor:ts:amount";
    private static final String TS_RISK = "workshop:txmonitor:ts:risk_score";
    private static final long RETENTION_MS = 3_600_000; // 1 hour

    public TransactionMonitorService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void init() {
        createTimeSeries(TS_COUNT, "SUM");
        createTimeSeries(TS_AMOUNT, "LAST");
        createTimeSeries(TS_RISK, "LAST");
    }

    @PreDestroy
    public void cleanup() {
        stopSimulation();
    }

    private void createTimeSeries(String key, String duplicatePolicy) {
        try {
            redis.execute((RedisCallback<Object>) connection ->
                    connection.execute("TS.CREATE",
                            key.getBytes(StandardCharsets.UTF_8),
                            "RETENTION".getBytes(StandardCharsets.UTF_8),
                            String.valueOf(RETENTION_MS).getBytes(StandardCharsets.UTF_8),
                            "DUPLICATE_POLICY".getBytes(StandardCharsets.UTF_8),
                            duplicatePolicy.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // Already exists — ignore
        }
    }

    private void tsAdd(String key, long timestamp, double value) {
        redis.execute((RedisCallback<Object>) connection ->
                connection.execute("TS.ADD",
                        key.getBytes(StandardCharsets.UTF_8),
                        String.valueOf(timestamp).getBytes(StandardCharsets.UTF_8),
                        String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tsRange(String key, long from, long to,
                                               String aggregation, long bucketMs) {
        List<Object> raw;
        try {
            raw = (List<Object>) redis.execute((RedisCallback<Object>) connection ->
                    connection.execute("TS.RANGE",
                            key.getBytes(StandardCharsets.UTF_8),
                            String.valueOf(from).getBytes(StandardCharsets.UTF_8),
                            String.valueOf(to).getBytes(StandardCharsets.UTF_8),
                            "AGGREGATION".getBytes(StandardCharsets.UTF_8),
                            aggregation.getBytes(StandardCharsets.UTF_8),
                            String.valueOf(bucketMs).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Collections.emptyList();
        }
        if (raw == null) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : raw) {
            if (entry instanceof List<?> pair && pair.size() == 2) {
                long ts = toLong(pair.get(0));
                double val = toDouble(pair.get(1));
                result.add(Map.of("timestamp", ts, "value", val));
            }
        }
        return result;
    }

    private long toLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof byte[] b) return Long.parseLong(new String(b, StandardCharsets.UTF_8));
        return Long.parseLong(o.toString());
    }

    private double toDouble(Object o) {
        if (o instanceof Double d) return d;
        if (o instanceof byte[] b) return Double.parseDouble(new String(b, StandardCharsets.UTF_8));
        return Double.parseDouble(o.toString());
    }

    public synchronized void startSimulation() {
        if (simulating) return;
        simulating = true;
        simulator = Executors.newSingleThreadScheduledExecutor();
        simulator.scheduleAtFixedRate(this::generateTransaction, 0, 500, TimeUnit.MILLISECONDS);
    }

    public synchronized void stopSimulation() {
        simulating = false;
        if (simulator != null) {
            simulator.shutdown();
            simulator = null;
        }
    }

    private void generateTransaction() {
        try {
            double amount = 10 + random.nextDouble() * 990;
            double riskScore = random.nextDouble() * 50; // normal: 0-50
            long ts = System.currentTimeMillis();
            tsAdd(TS_COUNT, ts, 1);
            tsAdd(TS_AMOUNT, ts, amount);
            tsAdd(TS_RISK, ts, riskScore);
        } catch (Exception e) {
            // Swallow to keep scheduler alive
        }
    }

    public void injectAnomaly() {
        long base = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            double amount = 5000 + random.nextDouble() * 15000;
            double riskScore = 70 + random.nextDouble() * 30;
            long ts = base + i;
            tsAdd(TS_COUNT, ts, 1);
            tsAdd(TS_AMOUNT, ts, amount);
            tsAdd(TS_RISK, ts, riskScore);
        }
    }

    public Map<String, Object> getMetrics() {
        long now = System.currentTimeMillis();
        long from60 = now - 60_000;
        long from10 = now - 10_000;

        List<Map<String, Object>> countSeries = tsRange(TS_COUNT, from60, now, "SUM", 1000);
        List<Map<String, Object>> amountSeries = tsRange(TS_AMOUNT, from60, now, "AVG", 1000);
        List<Map<String, Object>> riskSeries = tsRange(TS_RISK, from60, now, "AVG", 1000);

        // TPS: sum of last 10 seconds / 10
        List<Map<String, Object>> recent = tsRange(TS_COUNT, from10, now, "SUM", 1000);
        double totalRecent = recent.stream().mapToDouble(m -> ((Number) m.get("value")).doubleValue()).sum();
        double tps = totalRecent / 10.0;

        // Total count (last 60s)
        double totalCount = countSeries.stream().mapToDouble(m -> ((Number) m.get("value")).doubleValue()).sum();

        // Avg and max amount (last 60s)
        double avgAmount = amountSeries.stream().mapToDouble(m -> ((Number) m.get("value")).doubleValue())
                .average().orElse(0);
        double maxAmount = amountSeries.stream().mapToDouble(m -> ((Number) m.get("value")).doubleValue())
                .max().orElse(0);

        // High risk % (buckets where avg risk > 50)
        long highRiskBuckets = riskSeries.stream()
                .filter(m -> ((Number) m.get("value")).doubleValue() > 50).count();
        double highRiskPct = riskSeries.isEmpty() ? 0 :
                (highRiskBuckets * 100.0) / riskSeries.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tps", Math.round(tps * 10.0) / 10.0);
        result.put("totalCount", (int) totalCount);
        result.put("avgAmount", Math.round(avgAmount * 100.0) / 100.0);
        result.put("maxAmount", Math.round(maxAmount * 100.0) / 100.0);
        result.put("highRiskPct", Math.round(highRiskPct * 10.0) / 10.0);
        result.put("countSeries", countSeries);
        result.put("amountSeries", amountSeries);
        result.put("riskSeries", riskSeries);
        result.put("simulating", simulating);
        return result;
    }

    public Map<String, Object> getHistory(long from, long to, String aggregation, long bucketMs) {
        List<Map<String, Object>> countData = tsRange(TS_COUNT, from, to, aggregation, bucketMs);
        List<Map<String, Object>> amountData = tsRange(TS_AMOUNT, from, to, "AVG", bucketMs);
        return Map.of("countData", countData, "amountData", amountData);
    }

    public void reset() {
        stopSimulation();
        try { redis.delete(TS_COUNT); } catch (Exception ignored) {}
        try { redis.delete(TS_AMOUNT); } catch (Exception ignored) {}
        try { redis.delete(TS_RISK); } catch (Exception ignored) {}
        createTimeSeries(TS_COUNT, "SUM");
        createTimeSeries(TS_AMOUNT, "LAST");
        createTimeSeries(TS_RISK, "LAST");
    }

    public boolean isSimulating() {
        return simulating;
    }
}
