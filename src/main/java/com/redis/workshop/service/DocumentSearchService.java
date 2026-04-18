package com.redis.workshop.service;

import com.redis.workshop.config.DocumentDataLoader;
import com.redis.workshop.config.RedisSearchHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class DocumentSearchService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchService.class);
    private static final String INDEX_NAME = "idx:regulations";
    private static final String DOC_PREFIX = "workshop:docs:regulation:";
    private static final int VECTOR_DIM = 1536;

    private final StringRedisTemplate redis;
    private final RedisSearchHelper redisSearchHelper;
    private final OpenAiService openAiService;

    public DocumentSearchService(StringRedisTemplate redis, RedisSearchHelper redisSearchHelper, OpenAiService openAiService) {
        this.redis = redis;
        this.redisSearchHelper = redisSearchHelper;
        this.openAiService = openAiService;
    }

    /**
     * Full-text search using FT.SEARCH with text query.
     */
    public Map<String, Object> fullTextSearch(String query) {
        // Escape special RediSearch characters
        String escaped = escapeQuery(query);
        String ftQuery = escaped;

        List<Map<String, Object>> results = executeFtSearch(ftQuery, null);

        String cmd = "FT.SEARCH " + INDEX_NAME + " \"" + ftQuery + "\" WITHSCORES RETURN 6 title category summary content tags LIMIT 0 10";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "full-text");
        response.put("query", query);
        response.put("resultCount", results.size());
        response.put("results", results);
        response.put("redisCommand", cmd);
        response.put("redisCommands", List.of(cmd + " → " + results.size() + " documents matched"));
        return response;
    }

    /**
     * Vector similarity search using FT.SEARCH with KNN.
     */
    public Map<String, Object> vectorSearch(String query) {
        // Use real embeddings if OpenAI is configured, otherwise mock vectors
        float[] queryVector = getQueryVector(query);
        byte[] vectorBytes = RedisSearchHelper.vectorToBytes(queryVector);

        List<Map<String, Object>> results = executeVectorSearch(vectorBytes, "*", 10);

        boolean mockVectors = !openAiService.isConfigured();
        String embedLine = mockVectors
                ? "(mock vector generated — OpenAI not configured)"
                : "OpenAI text-embedding-3-small → 1536-dim query vector";
        String cmd = "FT.SEARCH " + INDEX_NAME + " \"*=>[KNN 10 @vector $BLOB AS score]\" RETURN 7 title category summary content tags score SORTBY score PARAMS 2 BLOB <vector_bytes> DIALECT 2";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "vector");
        response.put("query", query);
        response.put("mockVectors", mockVectors);
        response.put("resultCount", results.size());
        response.put("results", results);
        response.put("redisCommand", cmd);
        response.put("redisCommands", List.of(
                "EMBEDDING " + embedLine,
                cmd + " → " + results.size() + " nearest neighbors returned"
        ));
        return response;
    }

    /**
     * Hybrid search: full-text filter + vector KNN.
     */
    public Map<String, Object> hybridSearch(String query) {
        String escaped = escapeQuery(query);
        // Use real embeddings if OpenAI is configured, otherwise mock vectors
        float[] queryVector = getQueryVector(query);
        byte[] vectorBytes = RedisSearchHelper.vectorToBytes(queryVector);

        // Use the text query as pre-filter for KNN
        String preFilter = "(" + escaped + ")";
        List<Map<String, Object>> results = executeVectorSearch(vectorBytes, preFilter, 10);

        // If hybrid returns no results (filter too strict), fall back to pure vector
        if (results.isEmpty()) {
            results = executeVectorSearch(vectorBytes, "*", 10);
        }

        boolean mockVectors = !openAiService.isConfigured();
        String embedLine = mockVectors
                ? "(mock vector generated — OpenAI not configured)"
                : "OpenAI text-embedding-3-small → 1536-dim query vector";
        String cmd = "FT.SEARCH " + INDEX_NAME + " \"" + preFilter + "=>[KNN 10 @vector $BLOB AS score]\" RETURN 7 title category summary content tags score SORTBY score PARAMS 2 BLOB <vector_bytes> DIALECT 2";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "hybrid");
        response.put("query", query);
        response.put("mockVectors", mockVectors);
        response.put("resultCount", results.size());
        response.put("results", results);
        response.put("redisCommand", cmd);
        response.put("redisCommands", List.of(
                "EMBEDDING " + embedLine,
                cmd + " → " + results.size() + " hybrid results (pre-filter + KNN)"
        ));
        return response;
    }

    /** List all loaded regulation documents. */
    public List<Map<String, Object>> listDocuments() {
        List<Map<String, Object>> docs = new ArrayList<>();
        Set<String> keys = redis.keys(DOC_PREFIX + "*");
        if (keys == null) return docs;

        for (String key : keys) {
            Map<String, Object> doc = readJsonDoc(key);
            if (doc != null) docs.add(doc);
        }
        docs.sort(Comparator.comparing(d -> String.valueOf(d.getOrDefault("id", ""))));
        return docs;
    }

    // --- CRUD operations ---

    /**
     * Read a single document by ID using JSON.GET (excludes vector field for readability).
     */
    public Map<String, Object> getById(String id) {
        String key = DOC_PREFIX + id;
        String redisCmd = "JSON.GET " + key + " $.id $.title $.category $.summary $.content $.tags";

        Object result = redis.execute(connection -> {
            return connection.execute("JSON.GET",
                    key.getBytes(StandardCharsets.UTF_8),
                    "$.id".getBytes(StandardCharsets.UTF_8),
                    "$.title".getBytes(StandardCharsets.UTF_8),
                    "$.category".getBytes(StandardCharsets.UTF_8),
                    "$.summary".getBytes(StandardCharsets.UTF_8),
                    "$.content".getBytes(StandardCharsets.UTF_8),
                    "$.tags".getBytes(StandardCharsets.UTF_8));
        }, true);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("redisCommand", redisCmd);

        if (result == null) {
            response.put("status", "NOT_FOUND");
            response.put("message", "No document found with id: " + id);
            response.put("redisCommands", List.of(redisCmd + " → (nil) document not found"));
            return response;
        }

        String json = RedisSearchHelper.toStr(result);
        response.put("status", "OK");
        response.put("id", id);
        response.put("key", key);
        response.put("document", json);
        response.put("redisCommands", List.of(redisCmd + " → JSON document returned (" + (json != null ? json.length() : 0) + " chars)"));
        return response;
    }

    /**
     * Read a specific field from a document using JSON.GET with JSONPath.
     */
    public Map<String, Object> getField(String id, String path) {
        String key = DOC_PREFIX + id;
        String jsonPath = path.startsWith("$") ? path : "$." + path;
        String redisCmd = "JSON.GET " + key + " " + jsonPath;

        Object result = redis.execute(connection -> {
            return connection.execute("JSON.GET",
                    key.getBytes(StandardCharsets.UTF_8),
                    jsonPath.getBytes(StandardCharsets.UTF_8));
        }, true);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("redisCommand", redisCmd);

        if (result == null) {
            response.put("status", "NOT_FOUND");
            response.put("message", "No document or field found for key: " + key + " path: " + jsonPath);
            response.put("redisCommands", List.of(redisCmd + " → (nil) path not found"));
            return response;
        }

        String json = RedisSearchHelper.toStr(result);
        response.put("status", "OK");
        response.put("id", id);
        response.put("key", key);
        response.put("path", jsonPath);
        response.put("value", json);
        response.put("redisCommands", List.of(redisCmd + " → \"" + (json != null ? json : "") + "\""));
        return response;
    }

    /**
     * Create a new document using JSON.SET.
     */
    public Map<String, Object> createDocument(Map<String, Object> doc) {
        String docId = "custom:" + System.currentTimeMillis();
        String key = DOC_PREFIX + docId;

        // Build the JSON document
        Map<String, Object> fullDoc = new LinkedHashMap<>();
        fullDoc.put("id", docId);
        fullDoc.put("title", doc.getOrDefault("title", "Untitled"));
        fullDoc.put("category", doc.getOrDefault("category", "Custom"));
        fullDoc.put("summary", doc.getOrDefault("summary", ""));
        fullDoc.put("content", doc.getOrDefault("content", ""));
        fullDoc.put("tags", doc.getOrDefault("tags", "custom"));
        // Add a mock vector so it's indexed for search
        fullDoc.put("vector", DocumentDataLoader.generateVector(fullDoc.get("title") + " " + fullDoc.get("summary")));

        String json = toSimpleJson(fullDoc);
        String redisCmd = "JSON.SET " + key + " $ '" + json.replace("'", "\\'") + "'";

        redis.execute(connection -> {
            connection.execute("JSON.SET",
                    key.getBytes(StandardCharsets.UTF_8),
                    "$".getBytes(StandardCharsets.UTF_8),
                    json.getBytes(StandardCharsets.UTF_8));
            return null;
        }, true);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "CREATED");
        response.put("id", docId);
        response.put("key", key);
        response.put("document", fullDoc);
        response.put("redisCommand", redisCmd);
        response.put("redisCommands", List.of(redisCmd + " → OK (document created, auto-indexed)"));
        return response;
    }

    /**
     * Query documents by field value using FT.SEARCH.
     */
    public Map<String, Object> queryByField(String field, String value) {
        String ftQuery;
        if ("category".equals(field)) {
            // TAG field — use @category:{value}
            ftQuery = "@" + field + ":{" + escapeQuery(value) + "}";
        } else {
            // TEXT field — use @field:value
            ftQuery = "@" + field + ":" + escapeQuery(value);
        }

        String redisCmd = "FT.SEARCH " + INDEX_NAME + " \"" + ftQuery + "\" RETURN 5 title category summary content tags LIMIT 0 20";

        List<Object> rawResults = redisSearchHelper.ftSearchRaw(INDEX_NAME, ftQuery,
                "RETURN", "5", "title", "category", "summary", "content", "tags",
                "LIMIT", "0", "20");

        List<Map<String, Object>> results = parseSearchResults(rawResults, false, false);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("field", field);
        response.put("value", value);
        response.put("query", ftQuery);
        response.put("resultCount", results.size());
        response.put("results", results);
        response.put("redisCommand", redisCmd);
        response.put("redisCommands", List.of(redisCmd + " → " + results.size() + " documents matched"));
        return response;
    }

    // --- Private helpers ---

    /**
     * Get query vector: real OpenAI embedding if configured, deterministic mock otherwise.
     */
    private float[] getQueryVector(String query) {
        if (openAiService.isConfigured()) {
            try {
                return openAiService.getEmbedding(query);
            } catch (Exception e) {
                log.warn("Failed to get OpenAI embedding, falling back to mock vector: {}", e.getMessage());
            }
        }
        return DocumentDataLoader.generateVector(query);
    }

    private List<Map<String, Object>> executeFtSearch(String query, byte[] vectorBytes) {
        List<Object> rawResults = redisSearchHelper.ftSearchRaw(INDEX_NAME, query,
                "WITHSCORES",
                "RETURN", "6", "title", "category", "summary", "content", "tags", "$.id",
                "LIMIT", "0", "10");

        return parseSearchResults(rawResults, false, true);
    }

    private List<Map<String, Object>> executeVectorSearch(byte[] vectorBytes, String preFilter, int k) {
        String knnQuery = preFilter + "=>[KNN " + k + " @vector $BLOB AS score]";

        byte[][] binaryArgs = new byte[][] {
                knnQuery.getBytes(StandardCharsets.UTF_8),
                "RETURN".getBytes(StandardCharsets.UTF_8),
                "7".getBytes(StandardCharsets.UTF_8),
                "title".getBytes(StandardCharsets.UTF_8),
                "category".getBytes(StandardCharsets.UTF_8),
                "summary".getBytes(StandardCharsets.UTF_8),
                "content".getBytes(StandardCharsets.UTF_8),
                "tags".getBytes(StandardCharsets.UTF_8),
                "$.id".getBytes(StandardCharsets.UTF_8),
                "score".getBytes(StandardCharsets.UTF_8),
                "SORTBY".getBytes(StandardCharsets.UTF_8),
                "score".getBytes(StandardCharsets.UTF_8),
                "PARAMS".getBytes(StandardCharsets.UTF_8),
                "2".getBytes(StandardCharsets.UTF_8),
                "BLOB".getBytes(StandardCharsets.UTF_8),
                vectorBytes,
                "DIALECT".getBytes(StandardCharsets.UTF_8),
                "2".getBytes(StandardCharsets.UTF_8)
        };

        List<Object> rawResults = redisSearchHelper.ftSearchWithBinaryArgs(INDEX_NAME, binaryArgs);

        return parseSearchResults(rawResults, true, false);
    }

    // Continued in next section...
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSearchResults(Object rawResults, boolean hasScore, boolean withScores) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (rawResults == null) return results;

        // FT.SEARCH returns:
        //   Without WITHSCORES: [total, key1, [fields...], key2, [fields...], ...]
        //   With WITHSCORES:    [total, key1, score1, [fields...], key2, score2, [fields...], ...]
        List<Object> list;
        if (rawResults instanceof List<?> l) {
            list = (List<Object>) l;
        } else {
            return results;
        }

        if (list.size() < 2) return results;

        int step = withScores ? 3 : 2;
        // First element is the total count
        for (int i = 1; i < list.size(); i += step) {
            String docKey = RedisSearchHelper.toStr(list.get(i));

            double ftScore = 0.0;
            Object fieldsObj;
            if (withScores) {
                if (i + 2 >= list.size()) break;
                // Parse the relevance score returned by RediSearch
                try {
                    ftScore = Double.parseDouble(RedisSearchHelper.toStr(list.get(i + 1)));
                } catch (NumberFormatException e) {
                    ftScore = 0.0;
                }
                fieldsObj = list.get(i + 2);
            } else {
                if (i + 1 >= list.size()) break;
                fieldsObj = list.get(i + 1);
            }

            if (!(fieldsObj instanceof List<?> fields)) continue;

            Map<String, Object> doc = new LinkedHashMap<>();
            // Extract id from key
            if (docKey.startsWith(DOC_PREFIX)) {
                doc.put("id", docKey.substring(DOC_PREFIX.length()));
            }

            for (int j = 0; j + 1 < fields.size(); j += 2) {
                String fieldName = RedisSearchHelper.toStr(fields.get(j));
                String fieldValue = RedisSearchHelper.toStr(fields.get(j + 1));
                if ("$.id".equals(fieldName)) {
                    // JSON path returns quoted value
                    doc.put("id", fieldValue.replace("\"", "").replace("[", "").replace("]", ""));
                } else if ("score".equals(fieldName)) {
                    try {
                        double score = Double.parseDouble(fieldValue);
                        // Convert COSINE distance to similarity (1 - distance)
                        doc.put("score", Math.round((1.0 - score) * 1000.0) / 1000.0);
                    } catch (NumberFormatException e) {
                        doc.put("score", 0.0);
                    }
                } else {
                    doc.put(fieldName, fieldValue);
                }
            }

            // For WITHSCORES (full-text), store the raw score for later normalization
            if (withScores && !doc.containsKey("score")) {
                doc.put("score", ftScore);
            }

            if (!hasScore && !withScores && !doc.containsKey("score")) {
                doc.put("score", 1.0);
            }

            results.add(doc);
        }

        // Normalize full-text scores to 0-1 range using max score
        if (withScores && !results.isEmpty()) {
            double maxScore = results.stream()
                    .mapToDouble(d -> ((Number) d.getOrDefault("score", 0.0)).doubleValue())
                    .max().orElse(1.0);
            if (maxScore > 0) {
                for (Map<String, Object> doc : results) {
                    double raw = ((Number) doc.getOrDefault("score", 0.0)).doubleValue();
                    doc.put("score", Math.round((raw / maxScore) * 1000.0) / 1000.0);
                }
            }
        }

        return results;
    }

    private Map<String, Object> readJsonDoc(String key) {
        Object result = redis.execute(connection -> {
            return connection.execute("JSON.GET",
                    key.getBytes(StandardCharsets.UTF_8),
                    "$.id".getBytes(StandardCharsets.UTF_8),
                    "$.title".getBytes(StandardCharsets.UTF_8),
                    "$.category".getBytes(StandardCharsets.UTF_8),
                    "$.summary".getBytes(StandardCharsets.UTF_8),
                    "$.tags".getBytes(StandardCharsets.UTF_8));
        }, true);

        if (result == null) return null;
        String json = RedisSearchHelper.toStr(result);
        if (json == null || json.isEmpty()) return null;

        // Simple parse of the JSON response
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", extractJsonArrayValue(json, "$.id"));
        doc.put("title", extractJsonArrayValue(json, "$.title"));
        doc.put("category", extractJsonArrayValue(json, "$.category"));
        doc.put("summary", extractJsonArrayValue(json, "$.summary"));
        doc.put("tags", extractJsonArrayValue(json, "$.tags"));
        return doc;
    }

    private String extractJsonArrayValue(String json, String path) {
        // JSON.GET returns {"$.field":["value"]}
        String key = "\"" + path + "\":[\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = json.indexOf("\"]", start);
        if (end < 0) return "";
        return json.substring(start, end);
    }

    private static String escapeQuery(String query) {
        // Escape RediSearch special characters but keep spaces for multi-word search
        return query.replaceAll("([{}\\[\\]()\\\\@!\"~*<>:;./^$|&#+=-])", "\\\\$1");
    }

    private String toSimpleJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if ("vector".equals(e.getKey())) {
                // Serialize vector as JSON array
                if (!first) sb.append(",");
                first = false;
                sb.append("\"vector\":[");
                float[] vec = (float[]) e.getValue();
                for (int i = 0; i < vec.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(vec[i]);
                }
                sb.append("]");
                continue;
            }
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJsonStr(e.getKey())).append("\":");
            Object val = e.getValue();
            if (val instanceof String s) {
                sb.append("\"").append(escapeJsonStr(s)).append("\"");
            } else {
                sb.append(val);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJsonStr(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
