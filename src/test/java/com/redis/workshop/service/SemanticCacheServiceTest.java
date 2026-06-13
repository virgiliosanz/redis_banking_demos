package com.redis.workshop.service;

import com.redis.workshop.config.RedisSearchHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticCacheServiceTest {

    private LocalEmbeddingService localEmbeddingService;
    private RedisSearchHelper redisSearchHelper;
    private SemanticCacheService service;

    @BeforeEach
    void setUp() {
        localEmbeddingService = mock(LocalEmbeddingService.class);
        redisSearchHelper = mock(RedisSearchHelper.class);
        when(localEmbeddingService.getEmbedding(anyString())).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        when(redisSearchHelper.ftSearchWithBinaryArgs(anyString(), any(byte[][].class))).thenReturn(List.of(1L));
        service = new SemanticCacheService(mock(StringRedisTemplate.class), localEmbeddingService, redisSearchHelper);
    }

    @Test
    void should_returnCachedEntry_when_vectorDistanceIsBelowThreshold() {
        when(redisSearchHelper.parseSearchResults(anyList())).thenReturn(List.of(Map.of(
                "question", "What is PSD2?",
                "response", "Cached PSD2 answer",
                "__vector_score", "0.10"
        )));

        Map<String, String> result = service.checkSemanticCache("Explain PSD2");

        assertThat(result)
                .isNotNull()
                .containsEntry("question", "What is PSD2?")
                .containsEntry("response", "Cached PSD2 answer");
    }

    @Test
    void should_returnNull_when_bestMatchDistanceExceedsThreshold() {
        when(redisSearchHelper.parseSearchResults(anyList())).thenReturn(List.of(Map.of(
                "question", "What is Basel III?",
                "response", "Cached Basel answer",
                "__vector_score", "0.25"
        )));

        Map<String, String> result = service.checkSemanticCache("Explain Basel III");

        assertThat(result).isNull();
    }
}