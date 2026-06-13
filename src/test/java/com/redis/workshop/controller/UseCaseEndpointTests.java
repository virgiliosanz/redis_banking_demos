package com.redis.workshop.controller;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("property=\"og:title\" content=\"Redis Workshop | Banking Workshop Demo\"")))
                .andExpect(content().string(Matchers.containsString("apple-touch-icon")))
                .andExpect(content().string(Matchers.containsString("name=\"theme-color\" content=\"#091A23\"")))
                .andExpect(content().string(Matchers.containsString("fonts.googleapis.com/css2?family=Space+Grotesk")));
    }

    @Test
    void guidePage() throws Exception {
        mockMvc.perform(get("/guide"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("<title>Redis Workshop | Workshop Guide</title>")));
    }

    @Test
    void monitorPage() throws Exception {
        mockMvc.perform(get("/monitor"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Live Redis Commands")))
                .andExpect(content().string(Matchers.containsString("monitorCommandsOutput")))
                .andExpect(content().string(Matchers.containsString("href=\"/monitor\"")))
                .andExpect(content().string(Matchers.containsString("property=\"og:title\" content=\"Redis Workshop | Monitor Dashboard\"")));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17})
    void useCasePage(int uc) throws Exception {
        mockMvc.perform(get("/usecase/" + uc))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("<title>Redis Workshop | UC" + uc + ": ")))
                .andExpect(content().string(Matchers.containsString("property=\"og:title\" content=\"Redis Workshop | UC" + uc + ": ")))
                .andExpect(content().string(Matchers.containsString("property=\"og:type\" content=\"website\"")));
    }
}
