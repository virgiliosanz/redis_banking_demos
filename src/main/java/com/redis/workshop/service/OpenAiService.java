package com.redis.workshop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final String API_BASE = "https://api.openai.com/v1";

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.embedding-model:text-embedding-3-small}")
    private String embeddingModel;

    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (isConfigured()) {
            log.info("OpenAI integration enabled (model={}, embeddingModel={})", model, embeddingModel);
        } else {
            log.info("OpenAI integration disabled — no API key configured. Using mock fallback.");
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public float[] getEmbedding(String text) {
        List<float[]> results = getEmbeddings(List.of(text));
        return results.get(0);
    }

    public List<float[]> getEmbeddings(List<String> texts) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", embeddingModel);
            body.put("input", texts);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/embeddings"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenAI embeddings API error " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dataArray = root.get("data");
            List<float[]> results = new ArrayList<>();
            for (JsonNode item : dataArray) {
                JsonNode embedding = item.get("embedding");
                float[] vec = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vec[i] = (float) embedding.get(i).asDouble();
                }
                results.add(vec);
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get embeddings from OpenAI", e);
        }
    }

    public String streamChatCompletion(List<Map<String, String>> messages, SseEmitter emitter) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", true);
            body.put("temperature", 0.7);
            body.put("max_tokens", 1024);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("OpenAI chat API error " + response.statusCode() + ": " + errorBody);
            }

            StringBuilder fullResponse = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;

                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode delta = chunk.path("choices").path(0).path("delta");
                    JsonNode contentNode = delta.get("content");
                    if (contentNode != null && !contentNode.isNull()) {
                        String content = contentNode.asText();
                        fullResponse.append(content);
                        String tokenJson = objectMapper.writeValueAsString(Map.of("content", content));
                        emitter.send(SseEmitter.event().name("token").data(tokenJson));
                    }
                }
            }
            return fullResponse.toString();
        } catch (Exception e) {
            log.error("Error during streaming chat completion", e);
            throw new RuntimeException("Failed to stream chat completion", e);
        }
    }
}
