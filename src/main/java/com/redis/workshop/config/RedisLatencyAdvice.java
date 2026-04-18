package com.redis.workshop.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Map;

/**
 * Injects Redis-dominated handler latency into every /api/** response:
 *  - adds {@code redisLatencyMs} to Map bodies (if not already present)
 *  - always sets {@code X-Redis-Latency-Ms} response header
 *
 * Companion to {@link RedisLatencyInterceptor} which records start time.
 */
@ControllerAdvice
public class RedisLatencyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servletReq)) return body;
        HttpServletRequest http = servletReq.getServletRequest();
        String path = http.getRequestURI();
        if (path == null || !path.startsWith("/api/")) return body;
        // Skip non-JSON payloads (SSE streams, file downloads, etc.)
        if (selectedContentType != null && !MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }

        Object startAttr = http.getAttribute(RedisLatencyInterceptor.START_ATTR);
        if (!(startAttr instanceof Long startNanos)) return body;

        double latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        String latencyStr = String.format(Locale.ROOT, "%.2f", latencyMs);
        double rounded = Double.parseDouble(latencyStr);

        response.getHeaders().set("X-Redis-Latency-Ms", latencyStr);

        if (body instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (!map.containsKey("redisLatencyMs")) {
                try {
                    map.put("redisLatencyMs", rounded);
                } catch (UnsupportedOperationException ignored) {
                    // Immutable map (e.g. Map.of(...)) — header still exposes the value.
                }
            }
        }

        return body;
    }
}
