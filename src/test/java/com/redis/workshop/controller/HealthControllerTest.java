package com.redis.workshop.controller;

import com.redis.workshop.config.RedisMonitorService;
import com.redis.workshop.service.LocalEmbeddingService;
import com.redis.workshop.service.OpenAiService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void healthReturnsUpWhenAllDependenciesAreHealthy() {
        HealthController controller = buildController(
                new OpenAiService.OpenAiHealth(true, true, "gpt-4o-mini", 230L, null),
                new LocalEmbeddingService.EmbeddingHealth(true, "bge-small-en-v1.5", 384, 12L, null),
                false
        );

        Map<String, Object> body = controller.health().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("UP");
        assertThat(component(body, "redis")).containsEntry("status", "UP");
        assertThat(component(body, "openai"))
                .containsEntry("status", "UP")
                .containsEntry("configured", true)
                .containsEntry("reachable", true)
                .containsEntry("model", "gpt-4o-mini");
        assertThat(component(body, "embeddings"))
                .containsEntry("status", "UP")
                .containsEntry("loaded", true)
                .containsEntry("dimensions", 384);
        assertThat(body.get("timestamp")).isInstanceOf(String.class);
    }

    @Test
    void healthReturnsDegradedWhenRedisIsUpButOpenAiIsDown() {
        HealthController controller = buildController(
                new OpenAiService.OpenAiHealth(false, false, "gpt-4o-mini", null, "OPENAI_API_KEY not configured"),
                new LocalEmbeddingService.EmbeddingHealth(true, "bge-small-en-v1.5", 384, 12L, null),
                false
        );

        Map<String, Object> body = controller.health().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("DEGRADED");
        assertThat(component(body, "redis")).containsEntry("status", "UP");
        assertThat(component(body, "openai"))
                .containsEntry("status", "DOWN")
                .containsEntry("configured", false)
                .containsEntry("reachable", false);
    }

    @Test
    void healthReturnsDownWhenRedisFails() {
        HealthController controller = buildController(
                new OpenAiService.OpenAiHealth(true, true, "gpt-4o-mini", 230L, null),
                new LocalEmbeddingService.EmbeddingHealth(true, "bge-small-en-v1.5", 384, 12L, null),
                true
        );

        Map<String, Object> body = controller.health().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("DOWN");
        assertThat(component(body, "redis"))
                .containsEntry("status", "DOWN")
                .containsKey("error");
    }

    private HealthController buildController(OpenAiService.OpenAiHealth openAiHealth,
                                             LocalEmbeddingService.EmbeddingHealth embeddingHealth,
                                             boolean redisFailure) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisServerCommands serverCommands = mock(RedisServerCommands.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        LocalEmbeddingService localEmbeddingService = mock(LocalEmbeddingService.class);
        RedisMonitorService redisMonitor = mock(RedisMonitorService.class);

        when(redis.getConnectionFactory()).thenReturn(connectionFactory);
        if (redisFailure) {
            when(connectionFactory.getConnection()).thenThrow(new IllegalStateException("Redis unavailable"));
        } else {
            Properties memoryInfo = new Properties();
            memoryInfo.setProperty("used_memory_human", "5.2M");

            when(connectionFactory.getConnection()).thenReturn(connection);
            when(connection.ping()).thenReturn("PONG");
            when(connection.serverCommands()).thenReturn(serverCommands);
            when(serverCommands.info("memory")).thenReturn(memoryInfo);
            when(serverCommands.dbSize()).thenReturn(123L);
        }
        when(openAiService.ping()).thenReturn(openAiHealth);
        when(localEmbeddingService.isReady()).thenReturn(embeddingHealth);

        return new HealthController(redis, openAiService, localEmbeddingService, redisMonitor);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> component(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }
}