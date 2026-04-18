package com.redis.workshop.controller;

import com.redis.workshop.service.OpenAiService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final StringRedisTemplate redis;
    private final OpenAiService openAiService;

    public HealthController(StringRedisTemplate redis, OpenAiService openAiService) {
        this.redis = redis;
        this.openAiService = openAiService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");

        Map<String, Object> redisStatus = new LinkedHashMap<>();
        try {
            var connectionFactory = redis.getConnectionFactory();
            if (connectionFactory == null) {
                throw new IllegalStateException("Redis connection factory not available");
            }
            var connection = connectionFactory.getConnection();
            String pong = connection.ping();
            redisStatus.put("status", "UP");
            redisStatus.put("ping", pong);

            Properties info = connection.serverCommands().info("memory");
            if (info != null) {
                String usedMemory = info.getProperty("used_memory_human");
                if (usedMemory != null) redisStatus.put("usedMemory", usedMemory);
            }

            Long dbSize = connection.serverCommands().dbSize();
            if (dbSize != null) redisStatus.put("keys", dbSize);
        } catch (Exception e) {
            redisStatus.put("status", "DOWN");
            redisStatus.put("error", e.getMessage());
            status.put("status", "DEGRADED");
        }
        status.put("redis", redisStatus);

        Map<String, Object> openAiStatus = new LinkedHashMap<>();
        if (openAiService.isConfigured()) {
            openAiStatus.put("status", "UP");
            openAiStatus.put("configured", true);
        } else {
            openAiStatus.put("status", "DISABLED");
            openAiStatus.put("configured", false);
            openAiStatus.put("note", "Set OPENAI_API_KEY to enable AI features (UC8 vector search, UC9 assistant)");
        }
        status.put("openai", openAiStatus);

        return ResponseEntity.ok(status);
    }

    @GetMapping("/monitor")
    public ResponseEntity<Map<String, Object>> monitor() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        try {
            var connectionFactory = redis.getConnectionFactory();
            if (connectionFactory == null) {
                throw new IllegalStateException("Redis connection factory not available");
            }
            var connection = connectionFactory.getConnection();
            var server = connection.serverCommands();

            Properties serverInfo = server.info("server");
            Properties clientsInfo = server.info("clients");
            Properties memoryInfo = server.info("memory");
            Properties statsInfo = server.info("stats");
            Properties persistenceInfo = server.info("persistence");

            putLong(metrics, "uptime_seconds", serverInfo, "uptime_in_seconds");
            putString(metrics, "redis_version", serverInfo, "redis_version");
            putLong(metrics, "connected_clients", clientsInfo, "connected_clients");
            putString(metrics, "used_memory_human", memoryInfo, "used_memory_human");
            putString(metrics, "used_memory_peak_human", memoryInfo, "used_memory_peak_human");
            putLong(metrics, "total_commands_processed", statsInfo, "total_commands_processed");
            putLong(metrics, "instantaneous_ops_per_sec", statsInfo, "instantaneous_ops_per_sec");
            putLong(metrics, "keyspace_hits", statsInfo, "keyspace_hits");
            putLong(metrics, "keyspace_misses", statsInfo, "keyspace_misses");
            putLong(metrics, "last_save_time", persistenceInfo, "rdb_last_save_time");

            Long dbSize = server.dbSize();
            metrics.put("db_size", dbSize != null ? dbSize : 0L);

            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            metrics.put("error", e.getMessage());
            return ResponseEntity.status(503).body(metrics);
        }
    }

    private static void putString(Map<String, Object> out, String key, Properties info, String prop) {
        if (info == null) return;
        String value = info.getProperty(prop);
        if (value != null) out.put(key, value);
    }

    private static void putLong(Map<String, Object> out, String key, Properties info, String prop) {
        if (info == null) return;
        String value = info.getProperty(prop);
        if (value == null) return;
        try {
            out.put(key, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            out.put(key, value);
        }
    }
}
