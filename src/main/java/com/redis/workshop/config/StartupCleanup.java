package com.redis.workshop.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    public StartupCleanup(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void flush() {
        log.info("Cleaning workshop-owned Redis state (preserving AMS indices/keys)...");
        int deletedKeys = deleteWorkshopKeys();
        int droppedIndices = dropWorkshopIndices();
        log.info("Workshop cleanup complete: {} keys deleted, {} indices dropped",
                deletedKeys, droppedIndices);
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
