package com.redis.workshop.config;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility for SCAN-based key iteration.
 * Prefer this over the {@code KEYS} command — KEYS blocks the server on
 * the full keyspace, while SCAN iterates cursor-based in bounded batches.
 */
public final class RedisScanHelper {

    private static final long DEFAULT_SCAN_COUNT = 100;

    private RedisScanHelper() {}

    /**
     * Returns all keys matching the given pattern using SCAN (non-blocking).
     */
    public static Set<String> scanKeys(StringRedisTemplate redis, String pattern) {
        return redis.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(DEFAULT_SCAN_COUNT)
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return result;
        });
    }
}
