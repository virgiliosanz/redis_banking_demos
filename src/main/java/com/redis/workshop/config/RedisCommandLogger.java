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

    /**
     * Per-request capture of commands as "fullCommand → result" strings.
     * Services call {@link #startCapture()} at the start of a request-scoped
     * method and {@link #getCaptured()} at the end to attach the list to the
     * API response (so each use case can render its own Redis Commands panel).
     */
    private final ThreadLocal<List<String>> requestCapture = new ThreadLocal<>();

    public void startCapture() {
        requestCapture.set(new ArrayList<>());
    }

    public List<String> getCaptured() {
        List<String> captured = requestCapture.get();
        requestCapture.remove();
        return captured != null ? captured : new ArrayList<>();
    }

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
        String trimmedFull = fullCommand != null ? trimVectors(fullCommand) : null;
        String trimmedResult = result != null ? trimVectors(result) : null;
        if (trimmedFull != null) entry.put("fullCommand", trimmedFull);
        if (trimmedResult != null) entry.put("result", trimmedResult);

        log.addFirst(entry);
        while (log.size() > MAX_ENTRIES) {
            log.removeLast();
        }

        // Also append to per-request capture if one is active
        List<String> capture = requestCapture.get();
        if (capture != null) {
            String line;
            if (trimmedFull != null && trimmedResult != null) {
                line = trimmedFull + " → " + trimmedResult;
            } else if (trimmedFull != null) {
                line = trimmedFull;
            } else {
                line = command + " " + key + (detail != null ? " " + detail : "");
            }
            capture.add(line);
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
