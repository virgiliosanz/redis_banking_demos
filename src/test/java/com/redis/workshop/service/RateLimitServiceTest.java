package com.redis.workshop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new RateLimitService(redis);
    }

    @Test
    void should_allowRequestAndSetWindowExpiry_when_firstRequestStartsNewWindow() {
        when(valueOps.increment("uc4:client-a")).thenReturn(1L);
        when(redis.getExpire("uc4:client-a", TimeUnit.SECONDS)).thenReturn(60L);

        Map<String, Object> result = service.checkRateLimit("client-a");

        assertThat(result)
                .containsEntry("allowed", true)
                .containsEntry("currentCount", 1L)
                .containsEntry("remaining", 9L)
                .containsEntry("retryAfter", 0L)
                .containsEntry("ttl", 60L);
        verify(redis).expire("uc4:client-a", 60L, TimeUnit.SECONDS);
    }

    @Test
    void should_blockRequestAndExposeRetryAfter_when_limitIsExceeded() {
        when(valueOps.increment("uc4:client-b")).thenReturn(11L);
        when(redis.getExpire("uc4:client-b", TimeUnit.SECONDS)).thenReturn(42L);

        Map<String, Object> result = service.checkRateLimit("client-b");

        assertThat(result)
                .containsEntry("allowed", false)
                .containsEntry("currentCount", 11L)
                .containsEntry("remaining", 0L)
                .containsEntry("retryAfter", 42L)
                .containsEntry("ttl", 42L);
        verify(redis, never()).expire(eq("uc4:client-b"), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    void should_reportInactiveWindow_when_clientHasNoCurrentCounter() {
        when(valueOps.get("uc4:client-c")).thenReturn(null);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(null);

        Map<String, Object> result = service.getStatus("client-c");

        assertThat(result)
                .containsEntry("active", false)
                .containsEntry("currentCount", 0L)
                .containsEntry("remaining", 10L)
                .containsEntry("ttl", 0L);
    }
}