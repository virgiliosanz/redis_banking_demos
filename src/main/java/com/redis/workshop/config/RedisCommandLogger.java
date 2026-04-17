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
        log(useCase, command, key, null);
    }

    public void log(String useCase, String command, String key, String detail) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("useCase", useCase);
        entry.put("command", command);
        entry.put("key", key);
        if (detail != null) entry.put("detail", detail);

        log.addFirst(entry);
        while (log.size() > MAX_ENTRIES) {
            log.removeLast();
        }
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
}
