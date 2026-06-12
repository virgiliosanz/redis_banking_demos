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
    private static final int DIMENSION = 384;

    private final BgeSmallEnV15EmbeddingModel model;

    public LocalEmbeddingService() {
        long startedAt = System.currentTimeMillis();
        this.model = new BgeSmallEnV15EmbeddingModel();
        log.info("LocalEmbeddingService: BGE-small-en-v1.5 loaded ({} dims) in {}ms",
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
}