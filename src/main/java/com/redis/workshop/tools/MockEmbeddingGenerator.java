package com.redis.workshop.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.*;

/**
 * Generates kb-embeddings.json with real PDF content but MOCK vectors.
 * Use this when no OpenAI API key is available.
 * The mock vectors are deterministic (seeded from content hash) so they're
 * reproducible but won't produce meaningful semantic search results.
 *
 * For real embeddings, use EmbeddingGenerator with OPENAI_API_KEY set.
 *
 * Usage:
 *   ./mvnw compile exec:java \
 *     -Dexec.mainClass="com.redis.workshop.tools.MockEmbeddingGenerator" \
 *     -Dexec.classpathScope=compile
 */
public class MockEmbeddingGenerator {

    private static final int VECTOR_DIM = 1536;
    private static final String OUTPUT_FILE = "src/main/resources/data/kb-embeddings.json";

    public static void main(String[] args) throws Exception {
        var pdfs = List.of(
            Map.of("path", "src/main/resources/docs/psd2.pdf",
                   "id", "psd2", "title", "PSD2 - Payment Services Directive 2"),
            Map.of("path", "src/main/resources/docs/dora.pdf",
                   "id", "dora", "title", "DORA - Digital Operational Resilience Act"),
            Map.of("path", "src/main/resources/docs/mifid2.pdf",
                   "id", "mifid2", "title", "MiFID II - Markets in Financial Instruments Directive"),
            Map.of("path", "src/main/resources/docs/gdpr.pdf",
                   "id", "gdpr", "title", "GDPR - General Data Protection Regulation")
        );

        List<Map<String, Object>> allChunks = new ArrayList<>();

        for (var pdf : pdfs) {
            String path = pdf.get("path");
            if (!new File(path).exists()) {
                System.out.println("SKIP (not found): " + path);
                continue;
            }
            System.out.println("\nParsing " + pdf.get("title") + "...");
            var chunks = PdfChunker.chunkPdf(path, pdf.get("id"), pdf.get("title"));
            System.out.println("  → " + chunks.size() + " chunks");

            for (var chunk : chunks) {
                Map<String, Object> entry = new LinkedHashMap<>(chunk);
                entry.put("vector", generateMockVector(chunk.get("content")));
                allChunks.add(entry);
            }
        }

        // Ensure output directory exists
        new File(OUTPUT_FILE).getParentFile().mkdirs();

        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(OUTPUT_FILE), allChunks);
        System.out.println("\n✅ Saved " + allChunks.size() + " chunks with MOCK vectors to " + OUTPUT_FILE);
        System.out.println("⚠️  These are mock vectors. For real embeddings, run EmbeddingGenerator with OPENAI_API_KEY.");
    }

    private static float[] generateMockVector(String content) {
        long hash = 0;
        for (char c : content.toCharArray()) {
            hash = 31 * hash + c;
        }
        Random rng = new Random(hash);
        float[] vec = new float[VECTOR_DIM];
        double norm = 0;
        for (int i = 0; i < VECTOR_DIM; i++) {
            vec[i] = (float) rng.nextGaussian();
            norm += vec[i] * vec[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < VECTOR_DIM; i++) {
            vec[i] /= (float) norm;
        }
        return vec;
    }
}
