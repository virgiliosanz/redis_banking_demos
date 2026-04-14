package com.redis.workshop.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Flushes the entire Redis database on application startup to ensure
 * a clean state for the workshop. Runs before any data loaders via
 * {@code @DependsOn("startupCleanup")} on each loader component.
 */
@Component("startupCleanup")
public class StartupCleanup {

    private static final Logger log = LoggerFactory.getLogger(StartupCleanup.class);

    private final StringRedisTemplate redis;

    public StartupCleanup(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void flush() {
        log.info("🧹 Flushing Redis database for clean workshop state...");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        log.info("✅ Redis flushed successfully");
    }
}
