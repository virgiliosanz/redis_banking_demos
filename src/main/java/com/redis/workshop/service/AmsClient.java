package com.redis.workshop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.config.AmsProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP wrapper around the Agent Memory Server REST API. Each call records
 * a summarized request/response trace into {@link AmsTraceRecorder} for the
 * observability panel.
 *
 * The client speaks raw JSON via JDK {@link HttpClient} (matching the pattern
 * used by {@link OpenAiService}) so the demo remains dependency-free.
 */
@Service
public class AmsClient {

    private static final Logger log = LoggerFactory.getLogger(AmsClient.class);
    private static final int BODY_PREVIEW_CHARS = 4000;

    private final AmsProperties props;
    private final AmsTraceRecorder traces;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;

    public AmsClient(AmsProperties props, AmsTraceRecorder traces) {
        this.props = props;
        this.traces = traces;
    }

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("AMS client configured (baseUrl={}, namespace={})",
                props.getBaseUrl(), props.getDefaultNamespace());
    }

    public String baseUrl() { return props.getBaseUrl(); }
    public String namespace() { return props.getDefaultNamespace(); }

    // -------- Typed operations --------

    public Map<String, Object> health() {
        return exchange("health", "GET", "/v1/health", null, null);
    }

    public Map<String, Object> getWorkingMemory(String sessionId, String userId, String namespace) {
        Map<String, String> q = new LinkedHashMap<>();
        if (userId != null) q.put("user_id", userId);
        if (namespace != null) q.put("namespace", namespace);
        return exchange("getWorkingMemory", "GET",
                "/v1/working-memory/" + enc(sessionId), q, null);
    }

    public Map<String, Object> putWorkingMemory(String sessionId, Map<String, Object> memory,
                                                String userId, String namespace) {
        Map<String, Object> payload = new LinkedHashMap<>(memory);
        if (userId != null) payload.putIfAbsent("user_id", userId);
        if (namespace != null) payload.putIfAbsent("namespace", namespace);
        return exchange("putWorkingMemory", "PUT",
                "/v1/working-memory/" + enc(sessionId), null, payload);
    }

    public Map<String, Object> deleteWorkingMemory(String sessionId, String userId, String namespace) {
        Map<String, String> q = new LinkedHashMap<>();
        if (userId != null) q.put("user_id", userId);
        if (namespace != null) q.put("namespace", namespace);
        return exchange("deleteWorkingMemory", "DELETE",
                "/v1/working-memory/" + enc(sessionId), q, null);
    }

    public Map<String, Object> createLongTermMemories(List<Map<String, Object>> memories, boolean deduplicate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memories", memories);
        payload.put("deduplicate", deduplicate);
        return exchange("createLongTermMemories", "POST",
                "/v1/long-term-memory/", null, payload);
    }

    public Map<String, Object> searchLongTermMemories(Map<String, Object> searchRequest) {
        return exchange("searchLongTermMemories", "POST",
                "/v1/long-term-memory/search", null, searchRequest);
    }

    public Map<String, Object> memoryPrompt(Map<String, Object> request) {
        return exchange("memoryPrompt", "POST",
                "/v1/memory/prompt", null, request);
    }

    public Map<String, Object> deleteLongTermMemoriesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of("status", "ok", "skipped", true);
        StringBuilder qs = new StringBuilder();
        for (String id : ids) {
            if (qs.length() > 0) qs.append('&');
            qs.append("memory_ids=").append(enc(id));
        }
        String path = "/v1/long-term-memory?" + qs;
        return exchange("deleteLongTermMemoriesByIds", "DELETE", path, null, null);
    }

    // -------- Core HTTP + tracing --------

    private Map<String, Object> exchange(String operation, String method, String path,
                                         Map<String, String> query, Object body) {
        String fullPath = query == null || query.isEmpty() ? path : path + "?" + queryString(query);
        URI uri = URI.create(props.getBaseUrl() + fullPath);
        long start = System.currentTimeMillis();

        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");

        String requestBodyJson = null;
        try {
            if (body != null) {
                requestBodyJson = objectMapper.writeValueAsString(body);
                rb.header("Content-Type", "application/json");
                rb.method(method, HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8));
            } else {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            }
        } catch (Exception e) {
            throw new AmsException("Failed to serialize AMS request body for " + operation, e);
        }

        Map<String, Object> reqSummary = buildRequestSummary(method, fullPath, requestBodyJson);

        try {
            HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - start;
            String respBody = resp.body();
            Map<String, Object> parsed = parseBody(respBody);
            Map<String, Object> respSummary = buildResponseSummary(respBody, parsed);
            traces.record(method, fullPath, operation, reqSummary, respSummary, resp.statusCode(), ms, null);

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) return parsed;
            throw new AmsException(resp.statusCode(), respBody,
                    "AMS " + operation + " failed with HTTP " + resp.statusCode());
        } catch (AmsException e) {
            throw e;
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            String msg = "AMS " + operation + " unreachable at " + uri + ": " + e.getMessage();
            traces.record(method, fullPath, operation, reqSummary, null, 0, ms, msg);
            log.warn(msg);
            throw new AmsException(msg, e);
        }
    }

    private Map<String, Object> buildRequestSummary(String method, String path, String body) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("method", method);
        s.put("path", path);
        if (body != null) s.put("body", parseBodyOrRaw(body));
        return s;
    }

    private Map<String, Object> buildResponseSummary(String raw, Map<String, Object> parsed) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (parsed != null && !parsed.isEmpty()) {
            s.put("body", parsed);
        } else if (raw != null && !raw.isBlank()) {
            s.put("body", raw.length() > BODY_PREVIEW_CHARS ? raw.substring(0, BODY_PREVIEW_CHARS) + "…" : raw);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try {
            Object parsed = objectMapper.readValue(raw, new TypeReference<Object>() {});
            if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("value", parsed);
            return wrapper;
        } catch (Exception e) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("raw", raw.length() > BODY_PREVIEW_CHARS ? raw.substring(0, BODY_PREVIEW_CHARS) + "…" : raw);
            return wrapper;
        }
    }

    private Object parseBodyOrRaw(String raw) {
        try { return objectMapper.readValue(raw, new TypeReference<Object>() {}); }
        catch (Exception e) { return raw.length() > BODY_PREVIEW_CHARS ? raw.substring(0, BODY_PREVIEW_CHARS) + "…" : raw; }
    }

    private static String queryString(Map<String, String> q) {
        List<String> parts = new ArrayList<>(q.size());
        q.forEach((k, v) -> parts.add(enc(k) + "=" + enc(v)));
        return String.join("&", parts);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
