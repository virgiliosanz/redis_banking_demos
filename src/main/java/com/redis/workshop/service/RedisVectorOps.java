package com.redis.workshop.service;

import com.redis.workshop.config.DocumentDataLoader;
import com.redis.workshop.config.RedisSearchHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared Redis vector helpers used by UC9 services.
 * Keeps index creation, vector storage, and KNN search consistent across
 * {@link MemoryService}, {@link KnowledgeBaseService}, and {@link SemanticCacheService}.
 */
final class RedisVectorOps {

    private static final Logger log = LoggerFactory.getLogger(RedisVectorOps.class);

    private RedisVectorOps() {}

    /** Store vector field as raw bytes using RedisCallback (required for HNSW indexing). */
    static void storeVectorField(StringRedisTemplate redis, String key, float[] vector) {
        byte[] keyBytes = key.getBytes();
        byte[] vectorBytes = RedisSearchHelper.vectorToBytes(vector);
        redis.execute((RedisCallback<Object>) conn -> {
            conn.hashCommands().hSet(keyBytes, "vector".getBytes(), vectorBytes);
            return null;
        });
    }

    static void dropIndex(StringRedisTemplate redis, String indexName) {
        try {
            redis.execute((RedisCallback<Object>) conn ->
                    conn.execute("FT.DROPINDEX", indexName.getBytes()));
        } catch (Exception ignored) {}
    }

    /**
     * Create an HNSW vector index with the given extra schema fields.
     * Schema grammar: space-separated field definitions, e.g.
     *   "summary TEXT tags TAG SEPARATOR , date TEXT"
     */
    static void createVectorIndex(StringRedisTemplate redis, String indexName, String prefix,
                                  String extraSchemaFields, int vectorDim) {
        try {
            List<byte[]> argList = new ArrayList<>();
            argList.add(indexName.getBytes());
            argList.add("ON".getBytes());
            argList.add("HASH".getBytes());
            argList.add("PREFIX".getBytes());
            argList.add("1".getBytes());
            argList.add(prefix.getBytes());
            argList.add("SCHEMA".getBytes());
            for (String part : extraSchemaFields.split("\\s+")) {
                argList.add(part.getBytes());
            }
            argList.add("vector".getBytes());
            argList.add("VECTOR".getBytes());
            argList.add("HNSW".getBytes());
            argList.add("6".getBytes());
            argList.add("TYPE".getBytes());
            argList.add("FLOAT32".getBytes());
            argList.add("DIM".getBytes());
            argList.add(String.valueOf(vectorDim).getBytes());
            argList.add("DISTANCE_METRIC".getBytes());
            argList.add("COSINE".getBytes());

            byte[][] args = argList.toArray(new byte[0][]);
            redis.execute((RedisCallback<Object>) conn ->
                    conn.execute("FT.CREATE", args));
        } catch (Exception e) {
            log.info("Index creation note ({}): {}", indexName, e.getMessage());
        }
    }

    /**
     * KNN vector search using real OpenAI embeddings (or mock fallback) as the query vector.
     * Returns entries with redisKey, score, and passthrough fields (minus raw vector blob).
     */
    static List<Map<String, Object>> vectorSearch(RedisSearchHelper helper, OpenAiService openAiService,
                                                  String indexName, String query, int k) {
        float[] queryVector;
        try {
            queryVector = openAiService.getEmbedding(query);
        } catch (OpenAiException e) {
            log.warn("UC9: OpenAI embedding failed ({}), falling back to mock vector for KNN on {}", e.getMessage(), indexName);
            queryVector = DocumentDataLoader.generateVector(query);
        }
        byte[] vectorBytes = RedisSearchHelper.vectorToBytes(queryVector);

        String knnQuery = "*=>[KNN " + k + " @vector $BLOB]";
        byte[][] binaryArgs = new byte[][] {
                knnQuery.getBytes(),
                "PARAMS".getBytes(),
                "2".getBytes(),
                "BLOB".getBytes(),
                vectorBytes,
                "DIALECT".getBytes(),
                "2".getBytes()
        };

        List<Object> rawResult = helper.ftSearchWithBinaryArgs(indexName, binaryArgs);
        List<Map<String, String>> parsed = helper.parseSearchResults(rawResult);

        List<Map<String, Object>> results = new ArrayList<>();
        for (var doc : parsed) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("redisKey", doc.get("_key"));
            entry.put("score", doc.getOrDefault("__vector_score", "0"));
            for (var e : doc.entrySet()) {
                if (!e.getKey().equals("_key") && !e.getKey().equals("vector") && !e.getKey().equals("__vector_score")) {
                    entry.put(e.getKey(), e.getValue());
                }
            }
            results.add(entry);
        }
        return results;
    }

    /** Simple keyword scoring: +3 per matched tag, +1 per document hit, ignoring short tokens. */
    static int keywordScore(String query, String document, String tags) {
        int score = 0;
        String[] keywords = query.split("\\s+");
        for (String kw : keywords) {
            if (kw.length() < 3) continue;
            if (tags.contains(kw)) score += 3;
            if (document.contains(kw)) score += 1;
        }
        return score;
    }
}
