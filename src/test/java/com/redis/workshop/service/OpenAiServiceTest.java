package com.redis.workshop.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiServiceTest {

    @Test
    void chatCompletionRetriesRetryableStatusesThenSucceeds() {
        TestOpenAiService service = new TestOpenAiService();
        service.queueString(stringResponse(429, "rate limited"));
        service.queueString(stringResponse(500, "server error"));
        service.queueString(stringResponse(200, successBody("Recovered response")));

        String response = service.chatCompletion(messages());

        assertThat(response).isEqualTo("Recovered response");
        assertThat(service.stringAttempts).isEqualTo(3);
        assertThat(service.waits).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(service.lastStringRequest.timeout()).contains(Duration.ofSeconds(21));
    }

    @Test
    void chatCompletionDoesNotRetryClientErrors() {
        TestOpenAiService service = new TestOpenAiService();
        service.queueString(stringResponse(400, "bad request"));

        assertThatThrownBy(() -> service.chatCompletion(messages()))
                .isInstanceOf(OpenAiException.class)
                .satisfies(error -> {
                    OpenAiException ex = (OpenAiException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(400);
                    assertThat(ex.isRetryable()).isFalse();
                });

        assertThat(service.stringAttempts).isEqualTo(1);
        assertThat(service.waits).isEmpty();
    }

    @Test
    void streamChatCompletionRetriesRetryableStatusesThenStreamsTokens() throws Exception {
        TestOpenAiService service = new TestOpenAiService();
        service.queueStream(streamResponse(503, "temporarily unavailable"));
        service.queueStream(streamResponse(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n" +
                        "data: [DONE]\n"));

        SseEmitter emitter = mock(SseEmitter.class);
        doNothing().when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        String response = service.streamChatCompletion(messages(), emitter);

        assertThat(response).isEqualTo("Hello world");
        assertThat(service.streamAttempts).isEqualTo(2);
        assertThat(service.waits).containsExactly(Duration.ofMillis(500));
        assertThat(service.lastStreamRequest.timeout()).contains(Duration.ofSeconds(45));
    }

    @Test
    void circuitBreakerFastFailsAfterFiveFailedOperationsAndRecoversWithHalfOpenProbe() {
        TestOpenAiService service = new TestOpenAiService();
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 4; j++) {
                service.queueString(stringResponse(500, "server error"));
            }
        }

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.chatCompletion(messages()))
                    .isInstanceOf(OpenAiException.class)
                    .satisfies(error -> assertThat(((OpenAiException) error).getStatusCode()).isEqualTo(500));
        }

        int attemptsBeforeFastFail = service.stringAttempts;
        assertThatThrownBy(() -> service.chatCompletion(messages()))
                .isInstanceOf(OpenAiException.class)
                .satisfies(error -> {
                    OpenAiException ex = (OpenAiException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(503);
                    assertThat(ex.getMessage()).contains("circuit breaker OPEN");
                });
        assertThat(service.stringAttempts).isEqualTo(attemptsBeforeFastFail);

        service.advance(Duration.ofSeconds(30));
        service.queueString(stringResponse(200, successBody("Probe success")));
        assertThat(service.chatCompletion(messages())).isEqualTo("Probe success");

        service.queueString(stringResponse(200, successBody("Closed again")));
        assertThat(service.chatCompletion(messages())).isEqualTo("Closed again");
        assertThat(service.stringAttempts).isEqualTo(attemptsBeforeFastFail + 2);
    }

    private static List<Map<String, String>> messages() {
        return List.of(Map.of("role", "user", "content", "hello"));
    }

    private static String successBody(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> stringResponse(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> streamResponse(int status, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes()));
        return response;
    }

    private static final class TestOpenAiService extends OpenAiService {
        private final Queue<Object> stringQueue = new ArrayDeque<>();
        private final Queue<Object> streamQueue = new ArrayDeque<>();
        private final List<Duration> waits = new ArrayList<>();
        private Instant current = Instant.parse("2026-06-12T00:00:00Z");
        private int stringAttempts;
        private int streamAttempts;
        private HttpRequest lastStringRequest;
        private HttpRequest lastStreamRequest;

        private TestOpenAiService() {
            super("test-key", "gpt-4o-mini",
                    Duration.ofSeconds(9), Duration.ofSeconds(21), Duration.ofSeconds(45));
        }

        void queueString(HttpResponse<String> response) {
            stringQueue.add(response);
        }

        void queueStream(HttpResponse<InputStream> response) {
            streamQueue.add(response);
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        protected HttpResponse<String> sendStringRequest(HttpRequest request) {
            stringAttempts++;
            lastStringRequest = request;
            return next(stringQueue);
        }

        @Override
        protected HttpResponse<InputStream> sendStreamRequest(HttpRequest request) {
            streamAttempts++;
            lastStreamRequest = request;
            return next(streamQueue);
        }

        @Override
        protected Instant now() {
            return current;
        }

        @Override
        protected void sleep(Duration duration) {
            waits.add(duration);
            current = current.plus(duration);
        }

        @SuppressWarnings("unchecked")
        private <T> T next(Queue<Object> queue) {
            return (T) queue.remove();
        }
    }
}