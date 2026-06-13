package com.redis.workshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.config.RedisSearchHelper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoDatasetCoverageTest {

    @Test
    void userProfilesShouldExposeRicherSelectorData() {
        UserProfileService service = new UserProfileService(mock(StringRedisTemplate.class));

        List<Map<String, String>> users = service.listUsers();

        assertThat(users).hasSizeBetween(15, 20);
        assertThat(users).extracting(user -> user.get("segment"))
                .contains("Basic", "Premium", "Private Banking", "Business", "Student");
        assertThat(users).extracting(user -> user.get("country"))
                .contains("ES", "UK", "PT", "FR", "DE", "IT");
        assertThat(users).extracting(user -> user.get("accountType"))
                .contains("Premium Savings", "Business Current", "Student Account", "Joint Current");
    }

    @Test
    void featureStoreShouldListDiverseClients() {
        FeatureStoreService service = new FeatureStoreService(mock(StringRedisTemplate.class));

        List<Map<String, String>> clients = service.listClients();

        assertThat(clients).hasSizeBetween(10, 15);
        assertThat(clients).extracting(client -> client.get("segment"))
                .contains("Premium", "Business", "Standard", "Private Banking", "Student");
        assertThat(clients).extracting(client -> client.get("country"))
                .contains("ES", "UK", "RO", "FR", "DE", "PT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheAsideShouldExposeBroaderProductCatalog() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForHash()).thenReturn(hashOps);

        CacheAsideService service = new CacheAsideService(redis, new ObjectMapper());
        ReflectionTestUtils.setField(service, "loadData", true);
        ReflectionTestUtils.setField(service, "forceReload", true);
        service.init();

        List<Map<String, Object>> products = service.listProducts();

        assertThat(products).hasSizeBetween(10, 15);
        assertThat(products).extracting(product -> product.get("type"))
                .contains("Savings", "Checking", "Mortgage", "Credit Card", "Investment", "Business");
    }

    @Test
    void geoFinderShouldCoverMultipleCities() {
        GeoFinderService service = new GeoFinderService(
                mock(StringRedisTemplate.class),
                new ObjectMapper(),
                mock(RedisSearchHelper.class)
        );

        List<Map<String, Object>> branches = service.listAll();

        assertThat(branches).hasSizeBetween(20, 30);
        assertThat(branches).extracting(branch -> String.valueOf(branch.get("address")))
                .anyMatch(address -> address.contains("Madrid"))
                .anyMatch(address -> address.contains("Barcelona"))
                .anyMatch(address -> address.contains("Valencia"))
                .anyMatch(address -> address.contains("Lisbon"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fraudBaselineShouldSeedRicherVelocityHistory() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);

        FraudService service = new FraudService(redis);
        ReflectionTestUtils.setField(service, "loadData", true);
        ReflectionTestUtils.setField(service, "forceReload", true);
        service.loadBaselineData();

        verify(zSetOps, atLeast(18)).add(anyString(), anyString(), anyDouble());
    }
}