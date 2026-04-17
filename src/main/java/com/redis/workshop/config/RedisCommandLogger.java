package com.redis.workshop.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory ring buffer of recent Redis commands executed by the workshop services.
 * Services call {@link #log} after performing a Redis operation so the Monitor page
 * can display a live feed. This is an application-level log (not Redis MONITOR) to
 * keep the demo lightweight and avoid blocking commands on a web poll.
 */
@Component
public class RedisCommandLogger {

    private static final int MAX_ENTRIES = 50;
    private final Deque<Map<String, Object>> log = new ConcurrentLinkedDeque<>();

    public void log(String useCase, String command, String key) {
        log(useCase, command, key, null, null, null);
    }

    public void log(String useCase, String command, String key, String detail) {
        log(useCase, command, key, detail, null, null);
    }

    public void log(String useCase, String command, String key, String detail,
                    String fullCommand, String result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("useCase", useCase);
        entry.put("command", command);
        entry.put("key", key);
        if (detail != null) entry.put("detail", detail);
        if (fullCommand != null) entry.put("fullCommand", trimVectors(fullCommand));
        if (result != null) entry.put("result", trimVectors(result));

        log.addFirst(entry);
        while (log.size() > MAX_ENTRIES) {
            log.removeLast();
        }
    }

    /**
     * Trim vector arrays in strings to avoid flooding the UI.
     * Replaces [0.123, 0.456, ...many floats...] with [vector trimmed].
     */
    private String trimVectors(String input) {
        if (input == null) return null;
        return input.replaceAll("\\[(-?\\d+\\.\\d+,\\s*){3,}(-?\\d+\\.\\d+)\\]",
                "[vector trimmed]");
    }

    public List<Map<String, Object>> getRecentCommands(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> entry : log) {
            if (count >= limit) break;
            result.add(entry);
            count++;
        }
        return result;
    }

    /**
     * Return only entries newer than {@code sinceTimestamp} (exclusive).
     * The log is sorted newest-first and timestamps are ISO-8601 (UTC, lexicographically comparable),
     * so we iterate from the head and stop on the first entry that is not newer.
     */
    public List<Map<String, Object>> getCommandsSince(String sinceTimestamp, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (sinceTimestamp == null) return getRecentCommands(limit);
        for (Map<String, Object> entry : log) {
            String ts = (String) entry.get("timestamp");
            if (ts != null && ts.compareTo(sinceTimestamp) > 0) {
                result.add(entry);
                if (result.size() >= limit) break;
            } else {
                break;
            }
        }
        return result;
    }
}
