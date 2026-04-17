package com.redis.workshop.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that every Thymeleaf use case page (+ landing + guide) returns HTTP 200.
 * Uses the full Spring context with a live Redis (docker compose up).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UseCaseEndpointTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void homePage() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void guidePage() throws Exception {
        mockMvc.perform(get("/guide")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13})
    void useCasePage(int uc) throws Exception {
        mockMvc.perform(get("/usecase/" + uc)).andExpect(status().isOk());
    }
}
