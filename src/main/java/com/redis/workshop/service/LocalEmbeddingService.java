package com.redis.workshop.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15.BgeSmallEnV15EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class LocalEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);
    private static final String MODEL_NAME = "bge-small-en-v1.5";
    private static final int DIMENSION = 384;
    private static final String HEALTH_CHECK_TEXT = "Redis banking health check";

    private final BgeSmallEnV15EmbeddingModel model;

    public LocalEmbeddingService() {
        long startedAt = System.currentTimeMillis();
        this.model = new BgeSmallEnV15EmbeddingModel();
        log.info("LocalEmbeddingService: {} loaded ({} dims) in {}ms",
                MODEL_NAME,
                DIMENSION, System.currentTimeMillis() - startedAt);
    }

    public float[] getEmbedding(String text) {
        return model.embed(Objects.toString(text, "")).content().vector();
    }

    public List<float[]> getEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<TextSegment> segments = texts.stream()
                .map(text -> TextSegment.from(Objects.toString(text, "")))
                .toList();
        List<Embedding> embeddings = model.embedAll(segments).content();
        return embeddings.stream()
                .map(Embedding::vector)
                .toList();
    }

    public int getDimension() {
        return DIMENSION;
    }

    public EmbeddingHealth isReady() {
        long startedAt = System.currentTimeMillis();
        try {
            float[] vector = getEmbedding(HEALTH_CHECK_TEXT);
            int dimensions = vector == null ? 0 : vector.length;
            boolean loaded = dimensions == DIMENSION;
            String error = loaded ? null : "Unexpected embedding dimensions: " + dimensions;
            return new EmbeddingHealth(loaded, MODEL_NAME, dimensions, System.currentTimeMillis() - startedAt, error);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return new EmbeddingHealth(false, MODEL_NAME, DIMENSION, System.currentTimeMillis() - startedAt, message);
        }
    }

    public record EmbeddingHealth(boolean loaded, String model, int dimensions, long latencyMs, String error) {
        public String status() {
            return loaded ? "UP" : "DOWN";
        }
    }
}