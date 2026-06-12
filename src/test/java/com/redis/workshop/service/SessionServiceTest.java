package com.redis.workshop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private ValueOperations<String, String> valueOps;
    private SessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new SessionService(redis);
    }

    @Test
    void should_createSessionAndToken_when_credentialsAreValid() {
        Map<String, Object> result = service.login("user1", "password1");

        assertThat(result)
                .containsEntry("username", "user1")
                .containsEntry("sessionKey", "uc2:user:user1")
                .containsEntry("ttl", 300L);
        assertThat((String) result.get("tokenKey")).startsWith("uc2:token:");
        assertThat((String) result.get("token")).isNotBlank();

        ArgumentCaptor<Map<String, String>> sessionCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("uc2:user:user1"), sessionCaptor.capture());
        assertThat(sessionCaptor.getValue())
                .containsEntry("username", "user1")
                .containsEntry("fullName", "Ana García López")
                .containsEntry("role", "Premium Client")
                .containsKey("token")
                .containsKey("ipAddress");
        verify(redis).expire("uc2:user:user1", 300L, TimeUnit.SECONDS);
        verify(valueOps).set(
                argThat(key -> key.startsWith("uc2:token:")),
                eq("user1"),
                eq(300L),
                eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void should_returnNull_when_credentialsAreInvalid() {
        Map<String, Object> result = service.login("user1", "wrong-password");

        assertThat(result).isNull();
        verify(hashOps, never()).putAll(anyString(), org.mockito.ArgumentMatchers.anyMap());
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void should_returnSessionSnapshot_when_sessionExists() {
        when(hashOps.entries("uc2:user:user1")).thenReturn(Map.of("username", "user1", "token", "tok-1"));
        when(redis.getExpire("uc2:user:user1", TimeUnit.SECONDS)).thenReturn(120L);

        Map<String, Object> session = service.getSession("user1");

        assertThat(session)
                .containsEntry("username", "user1")
                .containsEntry("token", "tok-1")
                .containsEntry("sessionKey", "uc2:user:user1")
                .containsEntry("ttl", 120L);
    }

    @Test
    void should_deleteSessionAndToken_when_loggingOut() {
        when(hashOps.get("uc2:user:user1", "token")).thenReturn("tok-1");
        when(redis.delete("uc2:user:user1")).thenReturn(true);

        Map<String, Object> result = service.logout("user1");

        assertThat(result)
                .containsEntry("deleted", true)
                .containsEntry("sessionKey", "uc2:user:user1");
        verify(redis).delete("uc2:token:tok-1");
    }
}