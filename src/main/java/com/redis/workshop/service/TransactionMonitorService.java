package com.redis.workshop.service;

import jakarta.annotation.PreDestroy;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * UC11: Real-time Transaction Monitoring using Redis Streams.
 *
 * Uses XADD to ingest transaction events and XRANGE to query them,
 * with manual aggregation into time buckets (COUNT, AVG, SUM) in Java.
 */
@Service
public class TransactionMonitorService {

    private final StringRedisTemplate redis;
    private ScheduledExecutorService simulator;
    private volatile boolean simulating = false;
    private final Random random = new Random();

    private static final String STREAM_KEY = "workshop:txmonitor:stream";
    private static final long MAX_STREAM_LEN = 10_000; // MAXLEN for trimming

    public TransactionMonitorService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PreDestroy
    public void cleanup() {
        stopSimulation();
    }

    // ── Stream write: XADD ──────────────────────────────────────────────

    private void addTransaction(double amount, double riskScore) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("amount", String.format("%.2f", amount));
        fields.put("riskScore", String.format("%.1f", riskScore));

        MapRecord<String, String, String> record = StreamRecords.string(fields)
                .withStreamKey(STREAM_KEY);
        redis.opsForStream().add(record);

        // Trim to keep stream bounded — XTRIM MAXLEN ~
        redis.opsForStream().trim(STREAM_KEY, MAX_STREAM_LEN);
    }

    // ── Stream read: XRANGE with manual aggregation ─────────────────────

    /**
     * Read stream entries in a time range and aggregate into buckets.
     * Stream IDs are timestamp-based (e.g. "1713045600000-0"), so we
     * use millisecond-based range queries.
     */
    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> readRange(long fromMs, long toMs) {
        // Build stream ID range: "<fromMs>-0" to "<toMs>-99999"
        String fromId = fromMs + "-0";
        String toId = toMs + "-99999";
        return redis.opsForStream().range(STREAM_KEY,
                Range.closed(fromId, toId), Limit.limit().count(10000));
    }

    /**
     * Aggregate raw stream entries into time buckets.
     * Returns a list of {timestamp, value} maps per bucket.
     */
    private record BucketData(long timestamp, double sumAmount, double sumRisk,
                              int count, double maxAmount) {}

    private Map<Long, BucketData> aggregateIntoBuckets(
            List<MapRecord<String, Object, Object>> records, long bucketMs) {
        Map<Long, BucketData> buckets = new TreeMap<>();
        for (var record : records) {
            long ts = parseTimestamp(record.getId());
            long bucketKey = (ts / bucketMs) * bucketMs;
            double amount = parseDouble(record.getValue().get("amount"));
            double risk = parseDouble(record.getValue().get("riskScore"));

            buckets.compute(bucketKey, (k, existing) -> {
                if (existing == null) {
                    return new BucketData(bucketKey, amount, risk, 1, amount);
                }
                return new BucketData(bucketKey,
                        existing.sumAmount + amount,
                        existing.sumRisk + risk,
                        existing.count + 1,
                        Math.max(existing.maxAmount, amount));
            });
        }
        return buckets;
    }

    private long parseTimestamp(RecordId id) {
        String value = id.getValue();
        int dash = value.indexOf('-');
        return Long.parseLong(dash > 0 ? value.substring(0, dash) : value);
    }

    private double parseDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }

    // ── Simulation control ──────────────────────────────────────────────

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
            addTransaction(amount, riskScore);
        } catch (Exception e) {
            System.err.println("Failed to generate transaction: " + e.getMessage());
        }
    }

    public void injectAnomaly() {
        for (int i = 0; i < 20; i++) {
            double amount = 5000 + random.nextDouble() * 15000;
            double riskScore = 70 + random.nextDouble() * 30;
            addTransaction(amount, riskScore);
        }
    }

    // ── Metrics endpoint ────────────────────────────────────────────────

    public Map<String, Object> getMetrics() {
        long now = System.currentTimeMillis();
        long from60 = now - 60_000;
        long from10 = now - 10_000;
        long bucketMs = 1000;

        // Read all entries from last 60 seconds
        var records = readRange(from60, now);
        if (records == null) records = Collections.emptyList();

        // Aggregate into 1-second buckets
        Map<Long, BucketData> buckets = aggregateIntoBuckets(records, bucketMs);

        // Build series for charts
        List<Map<String, Object>> countSeries = new ArrayList<>();
        List<Map<String, Object>> amountSeries = new ArrayList<>();
        List<Map<String, Object>> riskSeries = new ArrayList<>();

        double totalCount = 0;
        double totalAmount = 0;
        double maxAmount = 0;
        long highRiskBuckets = 0;

        for (var entry : buckets.entrySet()) {
            BucketData b = entry.getValue();
            countSeries.add(Map.of("timestamp", b.timestamp, "value", b.count));
            double avgAmt = b.count > 0 ? b.sumAmount / b.count : 0;
            amountSeries.add(Map.of("timestamp", b.timestamp, "value",
                    Math.round(avgAmt * 100.0) / 100.0));
            double avgRisk = b.count > 0 ? b.sumRisk / b.count : 0;
            riskSeries.add(Map.of("timestamp", b.timestamp, "value",
                    Math.round(avgRisk * 10.0) / 10.0));

            totalCount += b.count;
            totalAmount += b.sumAmount;
            if (b.maxAmount > maxAmount) maxAmount = b.maxAmount;
            if (avgRisk > 50) highRiskBuckets++;
        }

        // TPS: count entries from last 10 seconds / 10
        double recentCount = records.stream()
                .filter(r -> parseTimestamp(r.getId()) >= from10)
                .count();
        double tps = recentCount / 10.0;

        double avgAmount = totalCount > 0 ? totalAmount / totalCount : 0;
        double highRiskPct = buckets.isEmpty() ? 0 :
                (highRiskBuckets * 100.0) / buckets.size();

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
        var records = readRange(from, to);
        if (records == null) records = Collections.emptyList();
        Map<Long, BucketData> buckets = aggregateIntoBuckets(records, bucketMs);

        List<Map<String, Object>> countData = new ArrayList<>();
        List<Map<String, Object>> amountData = new ArrayList<>();
        for (var entry : buckets.entrySet()) {
            BucketData b = entry.getValue();
            countData.add(Map.of("timestamp", b.timestamp, "value", b.count));
            double avgAmt = b.count > 0 ? b.sumAmount / b.count : 0;
            amountData.add(Map.of("timestamp", b.timestamp, "value",
                    Math.round(avgAmt * 100.0) / 100.0));
        }
        return Map.of("countData", countData, "amountData", amountData);
    }

    public void reset() {
        stopSimulation();
        try { redis.delete(STREAM_KEY); } catch (Exception ignored) {}
    }

    public boolean isSimulating() {
        return simulating;
    }
}
