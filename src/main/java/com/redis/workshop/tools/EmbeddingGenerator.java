package com.redis.workshop.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Standalone tool to parse EU regulation PDFs, generate OpenAI embeddings,
 * and save everything to a pre-computed JSON file.
 *
 * Usage:
 *   OPENAI_API_KEY=sk-... ./mvnw compile exec:java \
 *     -Dexec.mainClass="com.redis.workshop.tools.EmbeddingGenerator" \
 *     -Dexec.classpathScope=compile
 */
public class EmbeddingGenerator {

    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String OUTPUT_FILE = "src/main/resources/data/kb-embeddings.json";
    private static final int BATCH_SIZE = 20;

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void main(String[] args) throws Exception {
        if (OPENAI_API_KEY == null || OPENAI_API_KEY.isBlank()) {
            System.err.println("ERROR: Set OPENAI_API_KEY environment variable");
            System.exit(1);
        }

        // Define PDF sources
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
                System.out.println("  Embedded " + Math.min(i + BATCH_SIZE, chunks.size())
                        + "/" + chunks.size());

                // Small delay to avoid rate limiting
                if (i + BATCH_SIZE < chunks.size()) {
                    Thread.sleep(200);
                }
            }
        }

        // Ensure output directory exists
        new File(OUTPUT_FILE).getParentFile().mkdirs();

        // Save to JSON
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(OUTPUT_FILE), allChunks);
        System.out.println("\nSaved " + allChunks.size() + " chunks with embeddings to " + OUTPUT_FILE);
    }

    private static List<float[]> getEmbeddings(List<String> texts) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", EMBEDDING_MODEL);
        body.put("input", texts);
        String json = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/embeddings"))
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
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
    }
}
