package com.redis.workshop.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for all UC REST API endpoints.
 * Verifies each endpoint is reachable and returns a successful status code.
 * Requires Redis Stack running locally (docker compose up -d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiEndpointTests {

    @Autowired
    private MockMvc mockMvc;

    // -------- Health check --------
    @Test
    void healthCheck() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.redis.status").value("UP"))
                .andExpect(jsonPath("$.openai").exists());
    }

    // -------- UC1: Authentication Token Store --------
    @Test
    void uc1_authLogin() throws Exception {
        String body = "{\"username\":\"user1\",\"password\":\"password1\"}";
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    // -------- UC2: Session Storage --------
    @Test
    void uc2_sessionLogin() throws Exception {
        String body = "{\"username\":\"user1\",\"password\":\"password1\"}";
        mockMvc.perform(post("/api/session/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void uc2_sessionInfo() throws Exception {
        String body = "{\"username\":\"user1\",\"password\":\"password1\"}";
        mockMvc.perform(post("/api/session/login")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        mockMvc.perform(get("/api/session/info/user1"))
                .andExpect(status().isOk());
    }

    // -------- UC3: User Profile Storage --------
    @Test
    void uc3_profileUsers() throws Exception {
        mockMvc.perform(get("/api/profile/users")).andExpect(status().isOk());
    }

    @Test
    void uc3_profileLoadAndGet() throws Exception {
        mockMvc.perform(post("/api/profile/load/U1001")).andExpect(status().isOk());
        mockMvc.perform(get("/api/profile/U1001")).andExpect(status().isOk());
    }

    // -------- UC4: Rate Limiting --------
    @Test
    void uc4_rateLimitCheck() throws Exception {
        mockMvc.perform(post("/api/ratelimit/check")
                        .param("clientId", "test-client-uc4"))
                .andExpect(status().isOk());
    }

    // -------- UC5: Transaction Deduplication --------
    @Test
    void uc5_dedupSubmit() throws Exception {
        String body = "{\"sender\":\"alice\",\"receiver\":\"bob\",\"amount\":\"100.00\"}";
        mockMvc.perform(post("/api/dedup/submit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    // -------- UC6: Fraud Detection --------
    @Test
    void uc6_fraudEvaluate() throws Exception {
        String body = "{\"cardNumber\":\"4111111111111111\",\"amount\":\"50.00\"," +
                "\"merchant\":\"TestShop\",\"country\":\"ES\"}";
        mockMvc.perform(post("/api/fraud/evaluate")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    // -------- UC7: Feature Store --------
    @Test
    void uc7_featureClients() throws Exception {
        mockMvc.perform(get("/api/features/clients")).andExpect(status().isOk());
    }

    @Test
    void uc7_featureClient() throws Exception {
        mockMvc.perform(get("/api/features/client/C1001")).andExpect(status().isOk());
    }

    // -------- UC8: Document Database --------
    @Test
    void uc8_docsSearch() throws Exception {
        mockMvc.perform(get("/api/docs/search").param("q", "payment"))
                .andExpect(status().isOk());
    }

    @Test
    void uc8_docsList() throws Exception {
        mockMvc.perform(get("/api/docs/list")).andExpect(status().isOk());
    }

    // -------- UC9: AI Assistant --------
    @Test
    void uc9_assistantKb() throws Exception {
        mockMvc.perform(get("/api/assistant/kb")).andExpect(status().isOk());
    }

    @Test
    void uc9_assistantCacheStats() throws Exception {
        mockMvc.perform(get("/api/assistant/cache/stats")).andExpect(status().isOk());
    }

    // -------- UC10: Cache-Aside --------
    @Test
    void uc10_cacheProducts() throws Exception {
        mockMvc.perform(get("/api/cache/products")).andExpect(status().isOk());
    }

    @Test
    void uc10_cacheStats() throws Exception {
        mockMvc.perform(get("/api/cache/stats")).andExpect(status().isOk());
    }

    // -------- UC11: Transaction Monitoring --------
    @Test
    void uc11_transactionsMetrics() throws Exception {
        mockMvc.perform(get("/api/transactions/metrics")).andExpect(status().isOk());
    }

    // -------- UC12: Geospatial (ATM & Branch Finder) --------
    @Test
    void uc12_geoSearchNative() throws Exception {
        mockMvc.perform(get("/api/geo/search/native")
                        .param("lng", "-3.7038")
                        .param("lat", "40.4168")
                        .param("radius", "5"))
                .andExpect(status().isOk());
    }

    @Test
    void uc12_geoBranches() throws Exception {
        mockMvc.perform(get("/api/geo/branches")).andExpect(status().isOk());
    }

    // -------- UC13: Distributed Locking --------
    @Test
    void uc13_lockAcquire() throws Exception {
        String body = "{\"resourceId\":\"acc-test-uc13\"," +
                "\"clientId\":\"client-test\",\"ttlSeconds\":10}";
        mockMvc.perform(post("/api/lock/acquire")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
