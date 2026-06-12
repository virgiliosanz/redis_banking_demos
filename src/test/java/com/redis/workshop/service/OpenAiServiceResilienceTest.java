package com.redis.workshop.service;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiServiceResilienceTest {

    @Test
    void should_returnChatCompletion_when_openAiRespondsSuccessfully() {
        TimeoutAwareOpenAiService service = new TimeoutAwareOpenAiService();
        service.queueString(stringResponse(200, successBody("Stable response")));

        String response = service.chatCompletion(messages());

        assertThat(response).isEqualTo("Stable response");
        assertThat(service.lastRequest.timeout()).contains(Duration.ofSeconds(17));
    }

    @Test
    void should_wrapTimeoutFailure_when_httpClientTimesOut() {
        TimeoutAwareOpenAiService service = new TimeoutAwareOpenAiService();
        service.timeout = new HttpTimeoutException("chat timed out");

        assertThatThrownBy(() -> service.chatCompletion(messages()))
                .isInstanceOf(OpenAiException.class)
                .hasMessage("OpenAI request failed")
                .hasCauseInstanceOf(HttpTimeoutException.class);
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

    private static final class TimeoutAwareOpenAiService extends OpenAiService {
        private final Queue<HttpResponse<String>> responses = new ArrayDeque<>();
        private HttpRequest lastRequest;
        private Exception timeout;

        private TimeoutAwareOpenAiService() {
            super("test-key", "gpt-4o-mini",
                    Duration.ofSeconds(5), Duration.ofSeconds(17), Duration.ofSeconds(41));
        }

        private void queueString(HttpResponse<String> response) {
            responses.add(response);
        }

        @Override
        protected HttpResponse<String> sendStringRequest(HttpRequest request) throws Exception {
            lastRequest = request;
            if (timeout != null) {
                throw timeout;
            }
            return responses.remove();
        }
    }
}