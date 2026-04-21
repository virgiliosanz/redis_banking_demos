package com.redis.workshop.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded in-memory ring buffer of AMS request/response traces. Powers the new
 * use case's observability panel (AMS requests/responses + context assembly).
 *
 * Global scope only — for a single-instance workshop demo this is intentional:
 * trivial reset, visible in one place, no persistence overhead. Thread-safe via
 * a single synchronized deque (demo traffic is low and one demo session at a
 * time is the assumed workshop workflow).
 */
@Component
public class AmsTraceRecorder {

    public static final int DEFAULT_LIMIT = 50;

    private final Deque<Map<String, Object>> traces = new ArrayDeque<>();
    private final int maxEntries;

    public AmsTraceRecorder() {
        this.maxEntries = DEFAULT_LIMIT;
    }

    /** Record one trace. Oldest entries are evicted when the buffer is full. */
    public synchronized void record(String method, String path,
                                    String operation,
                                    Map<String, Object> requestSummary,
                                    Map<String, Object> responseSummary,
                                    int statusCode, long durationMs, String errorMessage) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("operation", operation);
        entry.put("method", method);
        entry.put("path", path);
        entry.put("statusCode", statusCode);
        entry.put("durationMs", durationMs);
        if (requestSummary != null) entry.put("request", requestSummary);
        if (responseSummary != null) entry.put("response", responseSummary);
        if (errorMessage != null) entry.put("error", errorMessage);
        traces.addFirst(entry);
        while (traces.size() > maxEntries) traces.removeLast();
    }

    /** Return a defensive copy of up to {@code limit} most recent traces. */
    public synchronized List<Map<String, Object>> recent(int limit) {
        int cap = Math.max(1, Math.min(limit, maxEntries));
        List<Map<String, Object>> out = new ArrayList<>(cap);
        for (Map<String, Object> e : traces) {
            if (out.size() >= cap) break;
            out.add(new LinkedHashMap<>(e));
        }
        return out;
    }

    public synchronized void clear() {
        traces.clear();
    }
}
