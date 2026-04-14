package com.redis.workshop.config;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reusable helper for FT.SEARCH commands via Lettuce native dispatch.
 * Spring Data Redis's connection.execute() uses ByteArrayOutput which cannot
 * handle the integer count that FT.SEARCH returns as its first element.
 * This helper uses NestedMultiOutput to properly handle mixed return types.
 */
@Component
public class RedisSearchHelper {

    private static final ProtocolKeyword FT_SEARCH = new ProtocolKeyword() {
        @Override public byte[] getBytes() { return "FT.SEARCH".getBytes(StandardCharsets.UTF_8); }
        @Override public String name() { return "FT.SEARCH"; }
    };

    private final StringRedisTemplate redisTemplate;

    public RedisSearchHelper(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Execute FT.SEARCH using Lettuce's native dispatch with NestedMultiOutput.
     * Returns raw List&lt;Object&gt; result for callers that need custom parsing.
     */
    @SuppressWarnings("unchecked")
    public List<Object> ftSearchRaw(String indexName, String query, String... extraArgs) {
        return redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<List<Object>>) connection -> {
            Object nativeConn = connection.getNativeConnection();
            if (!(nativeConn instanceof StatefulRedisConnection<?, ?> statefulConn)) {
                throw new IllegalStateException("Expected StatefulRedisConnection but got " + nativeConn.getClass());
            }

            ByteArrayCodec codec = ByteArrayCodec.INSTANCE;
            CommandArgs<byte[], byte[]> args = new CommandArgs<>(codec);
            args.add(indexName.getBytes(StandardCharsets.UTF_8));
            args.add(query.getBytes(StandardCharsets.UTF_8));
            for (String arg : extraArgs) {
                args.add(arg.getBytes(StandardCharsets.UTF_8));
            }

            NestedMultiOutput<byte[], byte[]> output = new NestedMultiOutput<>(codec);
            var typedConn = (StatefulRedisConnection<byte[], byte[]>) statefulConn;
            return typedConn.sync().dispatch(FT_SEARCH, output, args);
        });
    }

    /**
     * Execute FT.SEARCH and parse results into a list of field-value maps.
     */
    public List<Map<String, String>> ftSearch(String indexName, String query, String... extraArgs) {
        List<Object> rawResult = ftSearchRaw(indexName, query, extraArgs);
        return parseSearchResults(rawResult);
    }

    /**
     * Execute FT.SEARCH with binary args (for vector KNN queries with raw byte[] params).
     */
    @SuppressWarnings("unchecked")
    public List<Object> ftSearchWithBinaryArgs(String indexName, byte[][] allArgs) {
        return redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<List<Object>>) connection -> {
            Object nativeConn = connection.getNativeConnection();
            if (!(nativeConn instanceof StatefulRedisConnection<?, ?> statefulConn)) {
                throw new IllegalStateException("Expected StatefulRedisConnection but got " + nativeConn.getClass());
            }

            ByteArrayCodec codec = ByteArrayCodec.INSTANCE;
            CommandArgs<byte[], byte[]> args = new CommandArgs<>(codec);
            args.add(indexName.getBytes(StandardCharsets.UTF_8));
            for (byte[] arg : allArgs) {
                args.add(arg);
            }

            NestedMultiOutput<byte[], byte[]> output = new NestedMultiOutput<>(codec);
            var typedConn = (StatefulRedisConnection<byte[], byte[]>) statefulConn;
            return typedConn.sync().dispatch(FT_SEARCH, output, args);
        });
    }

    /**
     * Parse FT.SEARCH raw results into a list of maps.
     * FT.SEARCH returns: [count, key1, [field1, val1, ...], key2, [...], ...]
     */
    public List<Map<String, String>> parseSearchResults(List<Object> rawResult) {
        List<Map<String, String>> results = new ArrayList<>();
        if (rawResult == null || rawResult.size() < 2) return results;

        for (int i = 1; i < rawResult.size(); i += 2) {
            if (i + 1 >= rawResult.size()) break;
            String key = toStr(rawResult.get(i));
            Map<String, String> doc = new LinkedHashMap<>();
            doc.put("_key", key);

            Object fieldsObj = rawResult.get(i + 1);
            if (fieldsObj instanceof List<?> fields) {
                for (int j = 0; j < fields.size() - 1; j += 2) {
                    doc.put(toStr(fields.get(j)), toStr(fields.get(j + 1)));
                }
            }
            results.add(doc);
        }
        return results;
    }

    public static String toStr(Object obj) {
        if (obj == null) return "";
        if (obj instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (obj instanceof String s) return s;
        return obj.toString();
    }
}
