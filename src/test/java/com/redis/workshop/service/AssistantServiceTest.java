package com.redis.workshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssistantServiceTest {

    @Test
    void chatWithoutOpenAiReturnsConfigurationError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        OpenAiService openAiService = mock(OpenAiService.class);
        MemoryService memoryService = mock(MemoryService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        SemanticCacheService semanticCacheService = mock(SemanticCacheService.class);

        when(openAiService.isConfigured()).thenReturn(false);

        AssistantService service = new AssistantService(
                redis,
                openAiService,
                new ObjectMapper(),
                memoryService,
                knowledgeBaseService,
                semanticCacheService
        );

        var response = service.chat("sess-uc9", "Demo User", "What is Basel III?");

        assertThat(response)
                .containsEntry("sessionId", "sess-uc9")
                .containsEntry("error", "LLM not configured")
                .containsEntry("message", "Set OPENAI_API_KEY environment variable to enable AI assistant features.");
        verify(openAiService, never()).chatCompletion(org.mockito.ArgumentMatchers.anyList());
        verifyNoInteractions(memoryService, knowledgeBaseService, semanticCacheService);
    }
}