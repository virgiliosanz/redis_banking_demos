package com.redis.workshop.service;

import com.redis.workshop.config.AmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmsDemoServiceTest {

    private AmsClient amsClient;
    private AmsProperties properties;
    private OpenAiService openAiService;
    private AmsTraceRecorder traceRecorder;
    private AmsDemoService service;

    @BeforeEach
    void setUp() {
        amsClient = mock(AmsClient.class);
        properties = mock(AmsProperties.class);
        openAiService = mock(OpenAiService.class);
        traceRecorder = mock(AmsTraceRecorder.class);
        service = new AmsDemoService(amsClient, properties, traceRecorder, openAiService);

        when(properties.getDefaultNamespace()).thenReturn("workshop");
    }

    @Test
    void chatWithoutOpenAiReturnsConfigurationErrorAfterContextAssembly() {
        when(openAiService.isConfigured()).thenReturn(false);
        when(amsClient.getWorkingMemory("ams-1", "demo-user", "workshop"))
                .thenReturn(Map.of("messages", List.of()));
        when(amsClient.putWorkingMemory(eq("ams-1"), anyMap(), eq("demo-user"), eq("workshop")))
                .thenReturn(Map.of("messages", List.of(Map.of("role", "user", "content", "Need help with PSD2"))));
        when(amsClient.memoryPrompt(anyMap()))
                .thenReturn(Map.of("messages", List.of(
                        Map.of("role", "system", "content", "System prompt"),
                        Map.of("role", "user", "content", "Need help with PSD2")
                )));

        var response = service.chat("ams-1", "demo-user", "Need help with PSD2");

        assertThat(response)
                .containsEntry("sessionId", "ams-1")
                .containsEntry("userId", "demo-user")
                .containsEntry("namespace", "workshop")
                .containsEntry("openaiConfigured", false)
                .containsEntry("openaiUsed", false)
                .containsEntry("error", "LLM not configured")
                .containsEntry("message", "Set OPENAI_API_KEY environment variable to enable AI assistant features.");
        assertThat((List<?>) response.get("assembledMessages")).hasSize(2);
        verify(openAiService, never()).chatCompletion(anyList());
        verify(amsClient, times(1)).putWorkingMemory(eq("ams-1"), anyMap(), eq("demo-user"), eq("workshop"));
    }

    @Test
    void chatWithOpenAiUsesLiveCompletionAndPersistsAssistantReply() {
        when(openAiService.isConfigured()).thenReturn(true);
        when(openAiService.chatCompletion(anyList())).thenReturn("OpenAI reply");
        when(amsClient.getWorkingMemory("ams-2", "demo-user", "workshop"))
                .thenReturn(Map.of("messages", List.of()));
        when(amsClient.putWorkingMemory(eq("ams-2"), anyMap(), eq("demo-user"), eq("workshop")))
                .thenReturn(
                        Map.of("messages", List.of(Map.of("role", "user", "content", "Summarize MiFID II"))),
                        Map.of("messages", List.of(
                                Map.of("role", "user", "content", "Summarize MiFID II"),
                                Map.of("role", "assistant", "content", "OpenAI reply")
                        ))
                );
        when(amsClient.memoryPrompt(anyMap()))
                .thenReturn(Map.of("messages", List.of(
                        Map.of("role", "system", "content", "System prompt"),
                        Map.of("role", "user", "content", "Summarize MiFID II")
                )));

        var response = service.chat("ams-2", "demo-user", "Summarize MiFID II");

        assertThat(response)
                .containsEntry("response", "OpenAI reply")
                .containsEntry("openaiConfigured", true)
                .containsEntry("openaiUsed", true);
        verify(openAiService).chatCompletion(anyList());
        verify(amsClient, times(2)).putWorkingMemory(eq("ams-2"), anyMap(), eq("demo-user"), eq("workshop"));
    }
}