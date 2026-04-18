package com.redis.workshop.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the Redis latency interceptor to /api/** so every REST response
 * carries handler-level latency (used by the demo panels to show
 * sub-millisecond Redis performance).
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RedisLatencyInterceptor redisLatencyInterceptor;

    public WebMvcConfig(RedisLatencyInterceptor redisLatencyInterceptor) {
        this.redisLatencyInterceptor = redisLatencyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(redisLatencyInterceptor).addPathPatterns("/api/**");
    }
}
