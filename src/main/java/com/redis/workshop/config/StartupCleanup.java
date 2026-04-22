package com.redis.workshop.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scoped cleanup of workshop-owned Redis state on application startup.
 * Runs before any data loaders via {@code @DependsOn("startupCleanup")}.
 *
 * <p>Deliberately avoids {@code FLUSHALL}: Redis is shared with the Agent
 * Memory Server (UC14), which creates its own indices ({@code memory_records},
 * {@code working_memory_idx}) and key namespaces ({@code memory_idx:*},
 * {@code working_memory:*}) at startup. A blanket flush wiped those and
 * left UC14 seed permanently broken until the AMS container was restarted.
 *
 * <p>This class now only removes workshop-owned state:
 * <ul>
 *   <li>data keys matching {@code uc*:*} (every use case uses a {@code ucN:}
 *       prefix — see {@code *Service.KEY_PREFIX} constants)</li>
 *   <li>search indices matching {@code idx:uc*} (via {@code FT.DROPINDEX})</li>
 * </ul>
 * AMS state is left untouched, so its indices survive the Spring startup
 * and UC14 seed works without any manual AMS restart.
 */
@Component("startupCleanup")
public class StartupCleanup {

    private static final Logger log = LoggerFactory.getLogger(StartupCleanup.class);

    /** Every use case keys its data under {@code ucN:...}; see {@code *Service.KEY_PREFIX}. */
    private static final String KEY_PATTERN = "uc*:*";
    /** Every workshop search index is named {@code idx:ucN:...}; see {@code *Service.*_INDEX}. */
    private static final String INDEX_PREFIX = "idx:uc";

    private final StringRedisTemplate redis;

    /** Max time to wait for Redis to exit the {@code LOADING} state on startup. */
    @Value("${workshop.startup.redis-ready-timeout-seconds:60}")
    private long readyTimeoutSeconds;

    /** Poll interval between readiness probes while Redis is loading. */
    @Value("${workshop.startup.redis-ready-poll-millis:1000}")
    private long readyPollMillis;

    public StartupCleanup(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void flush() {
        awaitRedisReady();
        log.info("Cleaning workshop-owned Redis state (preserving AMS indices/keys)...");
        int deletedKeys = deleteWorkshopKeys();
        int droppedIndices = dropWorkshopIndices();
        log.info("Workshop cleanup complete: {} keys deleted, {} indices dropped",
                deletedKeys, droppedIndices);
    }

    /**
     * Waits until Redis accepts commands, tolerating the transient {@code LOADING}
     * state that occurs when Redis rehydrates its dataset from disk (RDB/AOF) on
     * startup. Uses {@code PING} as the readiness probe because Redis rejects
     * PING with {@code -LOADING} while the dataset is being loaded.
     *
     * <p>Bounded by {@code workshop.startup.redis-ready-timeout-seconds}
     * (default 60s). If the timeout is exceeded, throws
     * {@link IllegalStateException} so Spring fails fast with a clear message
     * instead of a generic {@code BeanCreationException}.
     */
    private void awaitRedisReady() {
        long deadline = System.currentTimeMillis() + readyTimeoutSeconds * 1000L;
        int attempts = 0;
        Exception lastError = null;
        boolean loggedLoading = false;
        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                String pong = redis.execute((RedisCallback<String>) conn -> conn.ping());
                if (pong != null) {
                    if (attempts > 1) {
                        log.info("Redis became ready after {} attempts", attempts);
                    }
                    return;
                }
            } catch (Exception e) {
                lastError = e;
                String msg = rootCauseMessage(e);
                boolean loading = msg != null && msg.toUpperCase(Locale.ROOT).contains("LOADING");
                if (loading && !loggedLoading) {
                    log.info("Redis is loading dataset from disk, waiting up to {}s...",
                            readyTimeoutSeconds);
                    loggedLoading = true;
                } else if (!loading) {
                    log.warn("Redis not ready (attempt {}): {}", attempts, msg);
                }
            }
            try {
                Thread.sleep(readyPollMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for Redis readiness", ie);
            }
        }
        throw new IllegalStateException(
                "Redis did not become ready within " + readyTimeoutSeconds
                        + "s (attempts=" + attempts
                        + "); aborting workshop startup cleanup. "
                        + "Increase workshop.startup.redis-ready-timeout-seconds "
                        + "or check that Redis is reachable and has finished loading.",
                lastError);
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }

    private int deleteWorkshopKeys() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, KEY_PATTERN);
        if (keys.isEmpty()) return 0;
        Long deleted = redis.delete(keys);
        return deleted == null ? 0 : deleted.intValue();
    }

    private int dropWorkshopIndices() {
        List<String> indices = listWorkshopIndices();
        int dropped = 0;
        for (String index : indices) {
            try {
                redis.execute((RedisCallback<Object>) conn ->
                        conn.execute("FT.DROPINDEX", index.getBytes(StandardCharsets.UTF_8)));
                dropped++;
            } catch (Exception e) {
                log.debug("FT.DROPINDEX {} skipped: {}", index, e.getMessage());
            }
        }
        return dropped;
    }

    private List<String> listWorkshopIndices() {
        List<String> out = new ArrayList<>();
        try {
            Object raw = redis.execute((RedisCallback<Object>) conn -> conn.execute("FT._LIST"));
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    String name = coerceIndexName(o);
                    if (name != null && name.startsWith(INDEX_PREFIX)) out.add(name);
                }
            }
        } catch (Exception e) {
            log.debug("FT._LIST unavailable, skipping index cleanup: {}", e.getMessage());
        }
        return out;
    }

    private static String coerceIndexName(Object o) {
        if (o == null) return null;
        if (o instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return o.toString();
    }
}
