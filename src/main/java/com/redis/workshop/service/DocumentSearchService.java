package com.redis.workshop.service;

import com.redis.workshop.config.DocumentDataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class DocumentSearchService {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchService.class);
    private static final String INDEX_NAME = "idx:regulations";
    private static final String DOC_PREFIX = "workshop:docs:regulation:";
    private static final int VECTOR_DIM = 768;

    private final StringRedisTemplate redis;

    public DocumentSearchService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Full-text search using FT.SEARCH with text query.
     */
    public Map<String, Object> fullTextSearch(String query) {
        // Escape special RediSearch characters
        String escaped = escapeQuery(query);
        String ftQuery = escaped;

        List<Map<String, Object>> results = executeFtSearch(ftQuery, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "full-text");
        response.put("query", query);
        response.put("resultCount", results.size());
        response.put("results", results);
        response.put("redisCommand", "FT.SEARCH " + INDEX_NAME + " \"" + ftQuery + "\" RETURN 6 title category summary content tags LIMIT 0 10");
        return response;
    }

    /**
     * Vector similarity search using FT.SEARCH with KNN.
     */
    public Map<String, Object> vectorSearch(String query) {
        // Generate query vector from the search text
        float[] queryVector = DocumentDataLoader.generateVector(query);
        byte[] vectorBytes = floatArrayToBytes(queryVector);

        List<Map<String, Object>> results = executeVectorSearch(vectorBytes, "*", 10);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "vector");
        response.put("query", query);
        response.put("resultCount", results.size());
        response.put("results", results);
        response.put("redisCommand", "FT.SEARCH " + INDEX_NAME + " \"*=>[KNN 10 @vector $BLOB AS score]\" RETURN 7 title category summary content tags score SORTBY score PARAMS 2 BLOB <vector_bytes> DIALECT 2");
        return response;
    }

    /**
     * Hybrid search: full-text filter + vector KNN.
     */
    public Map<String, Object> hybridSearch(String query) {
        String escaped = escapeQuery(query);
        float[] queryVector = DocumentDataLoader.generateVector(query);
        byte[] vectorBytes = floatArrayToBytes(queryVector);

        // Use the text query as pre-filter for KNN
        String preFilter = "(" + escaped + ")";
        List<Map<String, Object>> results = executeVectorSearch(vectorBytes, preFilter, 10);

        // If hybrid returns no results (filter too strict), fall back to pure vector
        if (results.isEmpty()) {
            results = executeVectorSearch(vectorBytes, "*", 10);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "hybrid");
        response.put("query", query);
        response.put("resultCount", results.size());
        response.put("results", results);
        response.put("redisCommand", "FT.SEARCH " + INDEX_NAME + " \"" + preFilter + "=>[KNN 10 @vector $BLOB AS score]\" RETURN 7 title category summary content tags score SORTBY score PARAMS 2 BLOB <vector_bytes> DIALECT 2");
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

    // --- Private helpers ---

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executeFtSearch(String query, byte[] vectorBytes) {
        Object rawResults = redis.execute(connection -> {
            return connection.execute("FT.SEARCH",
                    INDEX_NAME.getBytes(StandardCharsets.UTF_8),
                    query.getBytes(StandardCharsets.UTF_8),
                    "RETURN".getBytes(StandardCharsets.UTF_8),
                    "6".getBytes(StandardCharsets.UTF_8),
                    "title".getBytes(StandardCharsets.UTF_8),
                    "category".getBytes(StandardCharsets.UTF_8),
                    "summary".getBytes(StandardCharsets.UTF_8),
                    "content".getBytes(StandardCharsets.UTF_8),
                    "tags".getBytes(StandardCharsets.UTF_8),
                    "$.id".getBytes(StandardCharsets.UTF_8),
                    "LIMIT".getBytes(StandardCharsets.UTF_8),
                    "0".getBytes(StandardCharsets.UTF_8),
                    "10".getBytes(StandardCharsets.UTF_8));
        }, true);

        return parseSearchResults(rawResults, false);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executeVectorSearch(byte[] vectorBytes, String preFilter, int k) {
        String knnQuery = preFilter + "=>[KNN " + k + " @vector $BLOB AS score]";

        Object rawResults = redis.execute(connection -> {
            return connection.execute("FT.SEARCH",
                    INDEX_NAME.getBytes(StandardCharsets.UTF_8),
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
                    "2".getBytes(StandardCharsets.UTF_8));
        }, true);

        return parseSearchResults(rawResults, true);
    }

    // Continued in next section...
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSearchResults(Object rawResults, boolean hasScore) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (rawResults == null) return results;

        // FT.SEARCH returns: [total, key1, [field, val, ...], key2, [field, val, ...], ...]
        List<Object> list;
        if (rawResults instanceof List<?> l) {
            list = (List<Object>) l;
        } else {
            return results;
        }

        if (list.size() < 2) return results;

        // First element is the total count
        for (int i = 1; i < list.size(); i += 2) {
            if (i + 1 >= list.size()) break;

            String docKey = decodeObject(list.get(i));
            Object fieldsObj = list.get(i + 1);
            if (!(fieldsObj instanceof List<?> fields)) continue;

            Map<String, Object> doc = new LinkedHashMap<>();
            // Extract id from key
            if (docKey.startsWith(DOC_PREFIX)) {
                doc.put("id", docKey.substring(DOC_PREFIX.length()));
            }

            for (int j = 0; j + 1 < fields.size(); j += 2) {
                String fieldName = decodeObject(fields.get(j));
                String fieldValue = decodeObject(fields.get(j + 1));
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

            if (!hasScore && !doc.containsKey("score")) {
                doc.put("score", 1.0); // Full-text match = full relevance
            }

            results.add(doc);
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
        String json = decodeObject(result);
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

    private String decodeObject(Object obj) {
        if (obj == null) return "";
        if (obj instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return obj.toString();
    }

    private static byte[] floatArrayToBytes(float[] arr) {
        ByteBuffer buffer = ByteBuffer.allocate(arr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : arr) buffer.putFloat(f);
        return buffer.array();
    }

    private static String escapeQuery(String query) {
        // Escape RediSearch special characters but keep spaces for multi-word search
        return query.replaceAll("([{}\\[\\]()\\\\@!\"~*<>:;./^$|&#+=-])", "\\\\$1");
    }
}
