package com.redis.workshop.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15.BgeSmallEnV15EmbeddingModel;

import java.io.File;
import java.util.*;

/**
 * Standalone tool to parse EU regulation PDFs, generate local BGE embeddings,
 * and save everything to a pre-computed JSON file.
 *
 * Usage:
 *   ./mvnw compile exec:java \
 *     -Dexec.mainClass="com.redis.workshop.tools.EmbeddingGenerator" \
 *     -Dexec.classpathScope=compile
 */
public class EmbeddingGenerator {

    private static final int EMBEDDING_DIMENSION = 384;
    private static final String OUTPUT_FILE = "src/main/resources/data/kb-embeddings.json";
    private static final int BATCH_SIZE = 20;

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final BgeSmallEnV15EmbeddingModel model = new BgeSmallEnV15EmbeddingModel();

    public static void main(String[] args) throws Exception {
        System.out.println("Generating local BGE-small-en-v1.5 embeddings (" + EMBEDDING_DIMENSION + " dims)...");

        var pdfs = List.of(
            Map.of("path", "src/main/resources/docs/psd2.pdf",
                   "id", "psd2", "title", "PSD2 - Payment Services Directive 2"),
            Map.of("path", "src/main/resources/docs/dora.pdf",
                   "id", "dora", "title", "DORA - Digital Operational Resilience Act"),
            Map.of("path", "src/main/resources/docs/mifid2.pdf",
                   "id", "mifid2", "title", "MiFID II - Markets in Financial Instruments Directive"),
            Map.of("path", "src/main/resources/docs/gdpr.pdf",
                   "id", "gdpr", "title", "GDPR - General Data Protection Regulation"),
            Map.of("path", "src/main/resources/docs/euaiact.pdf",
                   "id", "euaiact", "title", "EU AI Act - Artificial Intelligence Act")
        );

        List<Map<String, Object>> allChunks = new ArrayList<>();

        for (var pdf : pdfs) {
            System.out.println("\nParsing " + pdf.get("title") + "...");
            var chunks = PdfChunker.chunkPdf(
                    pdf.get("path"), pdf.get("id"), pdf.get("title"));
            System.out.println("  → " + chunks.size() + " chunks");

            // Generate embeddings in batches
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                var batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
                var texts = batch.stream().map(c -> c.get("content")).toList();
                var embeddings = getEmbeddings(texts);

                for (int j = 0; j < batch.size(); j++) {
                    Map<String, Object> entry = new LinkedHashMap<>(batch.get(j));
                    entry.put("vector", embeddings.get(j));
                    allChunks.add(entry);
                }
                System.out.println("  Embedded " + Math.min(i + BATCH_SIZE, chunks.size()) + "/" + chunks.size());
            }
        }

        // Ensure output directory exists
        new File(OUTPUT_FILE).getParentFile().mkdirs();

        // Save to JSON
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(OUTPUT_FILE), allChunks);
        System.out.println("\nSaved " + allChunks.size() + " chunks with local BGE embeddings to " + OUTPUT_FILE);
    }

    private static List<float[]> getEmbeddings(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<TextSegment> segments = texts.stream()
                .map(TextSegment::from)
                .toList();
        List<Embedding> embeddings = model.embedAll(segments).content();
        return embeddings.stream()
                .map(Embedding::vector)
                .toList();
    }
}
