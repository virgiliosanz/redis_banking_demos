package com.redis.workshop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final String API_BASE = "https://api.openai.com/v1";
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 500, 502, 503);
    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
    );
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_CHAT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CIRCUIT_BREAKER_FAILURE_WINDOW = Duration.ofSeconds(60);
    private static final Duration CIRCUIT_BREAKER_OPEN_DURATION = Duration.ofSeconds(30);
    private static final int CIRCUIT_BREAKER_FAILURE_THRESHOLD = 5;
    private static final Duration HEALTH_CHECK_CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(15);

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.timeout.connect:10s}")
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

    @Value("${openai.timeout.chat:30s}")
    private Duration chatTimeout = DEFAULT_CHAT_TIMEOUT;

    @Value("${openai.timeout.stream:60s}")
    private Duration streamTimeout = DEFAULT_STREAM_TIMEOUT;

    private HttpClient httpClient;
    private CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile OpenAiHealth cachedHealth;
    private volatile long cachedHealthCheckedAtMillis;

    public OpenAiService() {
    }

    OpenAiService(String apiKey, String model, Duration connectTimeout, Duration chatTimeout, Duration streamTimeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.connectTimeout = connectTimeout;
        this.chatTimeout = chatTimeout;
        this.streamTimeout = streamTimeout;
        init();
    }

    @PostConstruct
    public void init() {
        if (connectTimeout == null) connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        if (chatTimeout == null) chatTimeout = DEFAULT_CHAT_TIMEOUT;
        if (streamTimeout == null) streamTimeout = DEFAULT_STREAM_TIMEOUT;
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .build();
        }
        if (circuitBreaker == null) {
            circuitBreaker = new CircuitBreaker();
        }
        if (isConfigured()) {
            log.info("OpenAI chat integration enabled (model={}, connectTimeout={}, chatTimeout={}, streamTimeout={})",
                    model, connectTimeout, chatTimeout, streamTimeout);
        } else {
            log.info("OpenAI chat integration disabled — no API key configured. AI features will return a configuration error.");
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public OpenAiHealth ping() {
        if (!isConfigured()) {
            return new OpenAiHealth(false, false, model, null,
                    "OPENAI_API_KEY not configured");
        }

        OpenAiHealth cached = cachedHealth;
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedHealthCheckedAtMillis < HEALTH_CHECK_CACHE_TTL.toMillis()) {
            return cached;
        }

        synchronized (this) {
            cached = cachedHealth;
            now = System.currentTimeMillis();
            if (cached != null && now - cachedHealthCheckedAtMillis < HEALTH_CHECK_CACHE_TTL.toMillis()) {
                return cached;
            }

            if (httpClient == null) {
                init();
            }

            OpenAiHealth fresh = fetchHealth();
            cachedHealth = fresh;
            cachedHealthCheckedAtMillis = System.currentTimeMillis();
            return fresh;
        }
    }

    private OpenAiHealth fetchHealth() {
        long startedAt = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(HEALTH_CHECK_TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startedAt;

            if (response.statusCode() != 200) {
                return new OpenAiHealth(true, false, model, latencyMs,
                        "OpenAI models API error " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("data").isArray()) {
                return new OpenAiHealth(true, false, model, latencyMs,
                        "OpenAI models API returned an unexpected payload");
            }

            return new OpenAiHealth(true, true, model, latencyMs, null);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            return new OpenAiHealth(true, false, model, latencyMs, healthErrorMessage(e));
        }
    }

    private String healthErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * Non-streaming chat completion. Used by the AMS use case where the frontend
     * renders assembled context + final response together rather than streaming
     * tokens. Returns the full assistant message text.
     */
    public String chatCompletion(List<Map<String, String>> messages) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.7);
            body.put("max_tokens", 1024);
            String json = objectMapper.writeValueAsString(body);

            return executeWithResilience("chatCompletion",
                    () -> buildChatRequest(json, chatTimeout),
                    request -> {
                        HttpResponse<String> response = sendStringRequest(request);
                        validateResponseStatus(response.statusCode(), response.body());
                        JsonNode root = objectMapper.readTree(response.body());
                        JsonNode content = root.path("choices").path(0).path("message").path("content");
                        return content.isMissingNode() || content.isNull() ? "" : content.asText();
                    });
        } catch (OpenAiException e) {
            throw e;
        } catch (Exception e) {
            throw wrapFailure("Failed to get chat completion from OpenAI", e);
        }
    }

    public String streamChatCompletion(List<Map<String, String>> messages, SseEmitter emitter) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", true);
            body.put("temperature", 0.7);
            body.put("max_tokens", 1024);
            String json = objectMapper.writeValueAsString(body);

            return executeWithResilience("streamChatCompletion",
                    () -> buildChatRequest(json, streamTimeout),
                    request -> {
                        HttpResponse<InputStream> response = sendStreamRequest(request);
                        if (response.statusCode() != 200) {
                            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                            throw createApiException(response.statusCode(), errorBody);
                        }

                        StringBuilder fullResponse = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                if (!line.startsWith("data: ")) continue;
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) break;

                                JsonNode chunk = objectMapper.readTree(data);
                                JsonNode delta = chunk.path("choices").path(0).path("delta");
                                JsonNode contentNode = delta.get("content");
                                if (contentNode != null && !contentNode.isNull()) {
                                    String content = contentNode.asText();
                                    fullResponse.append(content);
                                    String tokenJson = objectMapper.writeValueAsString(Map.of("content", content));
                                    emitter.send(SseEmitter.event().name("token").data(tokenJson));
                                }
                            }
                        }
                        return fullResponse.toString();
                    });
        } catch (OpenAiException e) {
            log.error("OpenAI API error during streaming chat completion: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error during streaming chat completion", e);
            throw wrapFailure("Failed to stream chat completion", e);
        }
    }

    protected HttpResponse<String> sendStringRequest(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<InputStream> sendStreamRequest(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    protected Instant now() {
        return Instant.now();
    }

    protected void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    private HttpRequest buildChatRequest(String json, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(timeout)
                .build();
    }

    private void validateResponseStatus(int statusCode, String responseBody) {
        if (statusCode != 200) {
            throw createApiException(statusCode, responseBody);
        }
    }

    private OpenAiException createApiException(int statusCode, String responseBody) {
        return new OpenAiException(statusCode, responseBody,
                "OpenAI chat API error " + statusCode,
                RETRYABLE_STATUS_CODES.contains(statusCode));
    }

    private OpenAiException wrapFailure(String message, Exception cause) {
        return new OpenAiException(message, cause);
    }

    private <T> T executeWithResilience(String operationName,
                                        ThrowingSupplier<HttpRequest> requestSupplier,
                                        ThrowingFunction<HttpRequest, T> action) throws Exception {
        circuitBreaker.beforeRequest();
        int retryIndex = 0;
        while (true) {
            try {
                T result = action.apply(requestSupplier.get());
                circuitBreaker.recordSuccess();
                return result;
            } catch (OpenAiException e) {
                if (shouldRetry(e, retryIndex)) {
                    Duration waitTime = RETRY_DELAYS.get(retryIndex);
                    logRetry(operationName, retryIndex + 1, e.getStatusCode(), waitTime);
                    sleep(waitTime);
                    retryIndex++;
                    continue;
                }
                if (shouldRecordCircuitFailure(e)) {
                    circuitBreaker.recordFailure();
                }
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                OpenAiException wrapped = new OpenAiException("OpenAI request interrupted", e);
                circuitBreaker.recordFailure();
                throw wrapped;
            } catch (Exception e) {
                OpenAiException wrapped = wrapFailure("OpenAI request failed", e);
                circuitBreaker.recordFailure();
                throw wrapped;
            }
        }
    }

    private boolean shouldRetry(OpenAiException exception, int retryIndex) {
        return exception.isRetryable() && retryIndex < RETRY_DELAYS.size();
    }

    private boolean shouldRecordCircuitFailure(OpenAiException exception) {
        return exception.isRetryable() || exception.getStatusCode() == 0;
    }

    private void logRetry(String operationName, int retryAttempt, int statusCode, Duration waitTime) {
        log.warn("OpenAI {} retry attempt {}/{} after status={} wait={}ms",
                operationName, retryAttempt, RETRY_DELAYS.size(), statusCode, waitTime.toMillis());
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws Exception;
    }

    private enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final class CircuitBreaker {
        private CircuitState state = CircuitState.CLOSED;
        private final Deque<Instant> failureTimes = new ArrayDeque<>();
        private Instant openUntil;
        private boolean halfOpenProbeInFlight;

        synchronized void beforeRequest() {
            Instant current = now();
            if (state == CircuitState.OPEN) {
                if (openUntil != null && current.isBefore(openUntil)) {
                    long remainingMs = Duration.between(current, openUntil).toMillis();
                    throw new OpenAiException(503, null,
                            "OpenAI circuit breaker OPEN. Retry after " + remainingMs + "ms.");
                }
                transitionTo(CircuitState.HALF_OPEN, "open wait elapsed; allowing one probe request");
            }

            if (state == CircuitState.HALF_OPEN) {
                if (halfOpenProbeInFlight) {
                    throw new OpenAiException(503, null,
                            "OpenAI circuit breaker HALF_OPEN. Probe request already in progress.");
                }
                halfOpenProbeInFlight = true;
            }
        }

        synchronized void recordSuccess() {
            failureTimes.clear();
            if (state == CircuitState.HALF_OPEN) {
                halfOpenProbeInFlight = false;
                openUntil = null;
                transitionTo(CircuitState.CLOSED, "probe request succeeded");
                return;
            }
            if (state == CircuitState.OPEN) {
                openUntil = null;
                transitionTo(CircuitState.CLOSED, "request succeeded");
            }
        }

        synchronized void recordFailure() {
            Instant current = now();
            if (state == CircuitState.HALF_OPEN) {
                halfOpenProbeInFlight = false;
                failureTimes.clear();
                openUntil = current.plus(CIRCUIT_BREAKER_OPEN_DURATION);
                transitionTo(CircuitState.OPEN, "probe request failed; reopening breaker for 30s");
                return;
            }

            trimFailures(current);
            failureTimes.addLast(current);
            if (failureTimes.size() >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
                failureTimes.clear();
                openUntil = current.plus(CIRCUIT_BREAKER_OPEN_DURATION);
                transitionTo(CircuitState.OPEN,
                        "5 consecutive failures within 60s; fast-failing requests for 30s");
            }
        }

        private void trimFailures(Instant current) {
            Instant cutoff = current.minus(CIRCUIT_BREAKER_FAILURE_WINDOW);
            while (!failureTimes.isEmpty() && failureTimes.peekFirst().isBefore(cutoff)) {
                failureTimes.removeFirst();
            }
        }

        private void transitionTo(CircuitState nextState, String reason) {
            if (state == nextState) {
                return;
            }
            CircuitState previous = state;
            state = nextState;
            if (nextState == CircuitState.OPEN) {
                log.warn("OpenAI circuit breaker transition {} -> {} ({})", previous, nextState, reason);
            } else {
                log.info("OpenAI circuit breaker transition {} -> {} ({})", previous, nextState, reason);
            }
        }
    }

    public record OpenAiHealth(boolean configured, boolean reachable, String model, Long latencyMs, String error) {
        public String status() {
            return configured && reachable ? "UP" : "DOWN";
        }
    }
}
