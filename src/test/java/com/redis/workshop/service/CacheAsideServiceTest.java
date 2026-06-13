package com.redis.workshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheAsideServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;
    private CacheAsideService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForHash()).thenReturn(hashOps);

        objectMapper = new ObjectMapper();
        service = new CacheAsideService(redis, objectMapper);
        ReflectionTestUtils.setField(service, "loadData", true);
        ReflectionTestUtils.setField(service, "forceReload", true);
        service.init();
    }

    @Test
    void should_returnCachedProduct_when_productExistsInRedisCache() throws Exception {
        Map<String, Object> product = findProduct("mortgage-fixed");
        when(valueOps.get("uc10:product:mortgage-fixed")).thenReturn(objectMapper.writeValueAsString(product));

        Map<String, Object> result = service.getProduct("mortgage-fixed");

        assertThat(result)
                .containsEntry("cacheHit", true)
                .containsEntry("source", "CACHE");
        assertThat((Map<String, Object>) result.get("product")).containsEntry("id", "mortgage-fixed");
        verify(valueOps, never()).set(eq("uc10:product:mortgage-fixed"), org.mockito.ArgumentMatchers.anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void should_loadProductFromMockDatabaseAndPopulateCache_when_cacheMissOccurs() {
        when(valueOps.get("uc10:product:business-loan")).thenReturn(null);

        Map<String, Object> result = service.getProduct("business-loan");

        assertThat(result)
                .containsEntry("cacheHit", false)
                .containsEntry("source", "DATABASE");
        assertThat((Map<String, Object>) result.get("product")).containsEntry("id", "business-loan");
        verify(valueOps).set(eq("uc10:product:business-loan"), org.mockito.ArgumentMatchers.anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void should_returnNotFoundError_when_productIsMissingFromCacheAndMockDatabase() {
        when(valueOps.get("uc10:product:missing-product")).thenReturn(null);

        Map<String, Object> result = service.getProduct("missing-product");

        assertThat(result)
                .containsEntry("error", "Product not found")
                .containsEntry("productId", "missing-product");
    }

    private Map<String, Object> findProduct(String productId) {
        List<Map<String, Object>> products = service.listProducts();
        return products.stream()
                .filter(product -> productId.equals(product.get("id")))
                .findFirst()
                .orElseThrow();
    }
}