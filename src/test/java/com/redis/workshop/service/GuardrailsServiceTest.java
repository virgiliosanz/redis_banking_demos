package com.redis.workshop.service;

import com.redis.workshop.config.RedisSearchHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardrailsServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;
    private StreamOperations<String, Object, Object> streamOps;
    private RedisSearchHelper redisSearchHelper;
    private LocalEmbeddingService localEmbeddingService;
    private OpenAiService openAiService;
    private GuardrailsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        streamOps = mock(StreamOperations.class);
        redisSearchHelper = mock(RedisSearchHelper.class);
        localEmbeddingService = mock(LocalEmbeddingService.class);
        openAiService = mock(OpenAiService.class);

        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(hashOps.entries(anyString())).thenReturn(Collections.emptyMap());
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(60L);
        when(localEmbeddingService.getEmbedding(anyString())).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        when(redisSearchHelper.ftSearchWithBinaryArgs(anyString(), any(byte[][].class))).thenReturn(List.of(1L));

        service = new GuardrailsService(redis, redisSearchHelper, localEmbeddingService, openAiService);
    }

    @Test
    void should_executeGuardrailPipelineAndScrubSensitiveData_when_requestIsAllowed() {
        when(openAiService.isConfigured()).thenReturn(true);
        when(redisSearchHelper.parseSearchResults(anyList())).thenReturn(
                List.of(Map.of(
                        "label", "investment",
                        "action", "allow",
                        "description", "investment route",
                        "severity", "review",
                        "score", "0.30"
                )),
                List.of(Map.of(
                        "pattern", "benign",
                        "severity", "low",
                        "response", "ok",
                        "score", "0.90"
                ))
        );
        when(openAiService.chatCompletion(anyList()))
                .thenReturn("This proposal offers guaranteed return for account 12345678.");

        Map<String, Object> response = service.chat("user-1", "Review my IBAN ES7620770024003102575766 investment options");

        assertThat(response)
                .containsEntry("status", "OK")
                .containsEntry("blocked", false)
                .containsEntry("route", "investment")
                .containsEntry("openaiUsed", true);
        assertThat((String) response.get("response"))
                .contains("potential return")
                .contains("not personalized financial advice")
                .doesNotContain("12345678")
                .contains("****5678");

        List<Map<String, Object>> pipeline = (List<Map<String, Object>>) response.get("pipeline");
        assertThat(pipeline).extracting(step -> step.get("stage"))
                .containsExactly("rateLimit", "topic", "inputPii", "promptInjection", "outputPii", "compliance");
        assertThat(pipeline.get(2)).containsEntry("status", "FLAG");
        assertThat(pipeline.get(4)).containsEntry("status", "FLAG");
    }

    @Test
    void should_returnConfigurationError_when_openAiIsNotConfiguredAfterGuardrailsPass() {
        when(openAiService.isConfigured()).thenReturn(false);
        when(redisSearchHelper.parseSearchResults(anyList())).thenReturn(
                List.of(Map.of(
                        "label", "support",
                        "action", "allow",
                        "description", "support route",
                        "severity", "standard",
                        "score", "0.20"
                )),
                List.of(Map.of(
                        "pattern", "benign",
                        "severity", "low",
                        "response", "ok",
                        "score", "0.90"
                ))
        );

        Map<String, Object> response = service.chat("user-2", "I need help with my password");

        assertThat(response)
                .containsEntry("status", "ERROR")
                .containsEntry("error", "LLM not configured")
                .containsEntry("openaiUsed", false);
        List<Map<String, Object>> pipeline = (List<Map<String, Object>>) response.get("pipeline");
        assertThat(pipeline).extracting(step -> step.get("stage"))
                .containsExactly("rateLimit", "topic", "inputPii", "promptInjection", "response");
        assertThat(pipeline.get(4)).containsEntry("status", "BLOCK");
        verify(openAiService, never()).chatCompletion(anyList());
    }
}