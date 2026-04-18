package com.redis.workshop.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Records request start time so {@link RedisLatencyAdvice} can compute
 * and expose the server-side handler latency (dominated by Redis ops)
 * for every /api/** endpoint.
 */
@Component
public class RedisLatencyInterceptor implements HandlerInterceptor {

    public static final String START_ATTR = "__workshop.redisLatencyStartNanos";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        return true;
    }
}
