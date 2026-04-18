package com.redis.workshop.controller;

import com.redis.workshop.config.RedisMonitorService;
import com.redis.workshop.service.OpenAiService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final StringRedisTemplate redis;
    private final OpenAiService openAiService;
    private final RedisMonitorService redisMonitor;

    public HealthController(StringRedisTemplate redis, OpenAiService openAiService,
                            RedisMonitorService redisMonitor) {
        this.redis = redis;
        this.openAiService = openAiService;
        this.redisMonitor = redisMonitor;
    }

    /**
     * Expose the last N commands captured by {@link RedisMonitorService} via
     * the Redis MONITOR stream. Used by the workshop UI to show a live feed
     * of commands without embedding them into every endpoint response.
     */
    @GetMapping("/redis/commands")
    public ResponseEntity<Map<String, Object>> redisCommands(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) String uc) {
        List<Map<String, Object>> commands = redisMonitor.getCommandsSince(since, limit);

        if (uc != null && !uc.isEmpty()) {
            String ucFilter = uc.toUpperCase();
            commands = commands.stream()
                    .filter(c -> ucFilter.equals(c.get("useCase")))
                    .collect(Collectors.toList());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", redisMonitor.isRunning());
        body.put("count", commands.size());
        body.put("commands", commands);
        return ResponseEntity.ok(body);
    }

    /**
     * Server-Sent Events stream of Redis commands in real-time. Frontend
     * subscribes per UC so the commands panel updates the instant a command
     * hits the MONITOR feed, avoiding polling races.
     */
    @GetMapping(value = "/redis/commands/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCommands(@RequestParam(required = false) String uc) {
        SseEmitter emitter = new SseEmitter(0L);

        String listenerId = UUID.randomUUID().toString();
        String ucFilter = (uc != null && !uc.isEmpty()) ? uc.toUpperCase() : null;

        redisMonitor.addListener(listenerId, command -> {
            try {
                if (ucFilter != null && !ucFilter.equals(command.get("useCase"))) return;
                emitter.send(SseEmitter.event()
                        .name("command")
                        .data(command));
            } catch (Exception e) {
                redisMonitor.removeListener(listenerId);
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        });

        emitter.onCompletion(() -> redisMonitor.removeListener(listenerId));
        emitter.onTimeout(() -> redisMonitor.removeListener(listenerId));
        emitter.onError(e -> redisMonitor.removeListener(listenerId));

        return emitter;
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
