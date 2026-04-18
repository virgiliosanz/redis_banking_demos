package com.redis.workshop.config;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reusable helper for FT.SEARCH commands via Lettuce native dispatch.
 * Spring Data Redis's connection.execute() uses ByteArrayOutput which cannot
 * handle the integer count that FT.SEARCH returns as its first element.
 * This helper uses NestedMultiOutput to properly handle mixed return types.
 *
 * <p>Redis 8 defaults to RESP3 which returns FT.SEARCH results as a map
 * ({@code total_results}, {@code results}, {@code attributes}, {@code format},
 * {@code warning}) rather than the RESP2 flat array. Callers across this
 * codebase were written against the RESP2 layout
 * {@code [count, key1, [fields1], key2, [fields2], ...]}, so {@code ftSearchRaw}
 * and {@code ftSearchWithBinaryArgs} detect RESP3 responses and normalize them
 * back to that flat layout. When the caller requested {@code WITHSCORES}, the
 * score is inlined between the key and its fields list, matching RESP2
 * {@code [count, key1, score1, [fields1], ...]}.
 */
@Component
public class RedisSearchHelper {

    private static final Logger log = LoggerFactory.getLogger(RedisSearchHelper.class);

    private static final ProtocolKeyword FT_SEARCH = new ProtocolKeyword() {
        @Override public byte[] getBytes() { return "FT.SEARCH".getBytes(StandardCharsets.UTF_8); }
        @Override public String name() { return "FT.SEARCH"; }
    };

    private final StringRedisTemplate redisTemplate;

    public RedisSearchHelper(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Resolve a StatefulRedisConnection from the native connection object.
     * Handles StatefulRedisConnection, async commands, and sync commands wrappers.
     */
    @SuppressWarnings("unchecked")
    private StatefulRedisConnection<byte[], byte[]> resolveStatefulConnection(Object nativeConn) {
        if (nativeConn instanceof StatefulRedisConnection<?, ?> src) {
            return (StatefulRedisConnection<byte[], byte[]>) src;
        } else if (nativeConn instanceof io.lettuce.core.api.async.RedisAsyncCommands<?, ?> asyncCommands) {
            return (StatefulRedisConnection<byte[], byte[]>) asyncCommands.getStatefulConnection();
        } else if (nativeConn instanceof io.lettuce.core.api.sync.RedisCommands<?, ?> syncCommands) {
            return (StatefulRedisConnection<byte[], byte[]>) syncCommands.getStatefulConnection();
        } else {
            throw new IllegalStateException("Unsupported native connection type: " + nativeConn.getClass());
        }
    }

    /**
     * Execute FT.SEARCH using Lettuce's native dispatch with NestedMultiOutput.
     * Returns a RESP2-flat layout regardless of protocol version:
     * <pre>[count, key1, (score1)?, [f1,v1,...], key2, (score2)?, [...], ...]</pre>
     * (score elements are only present when {@code WITHSCORES} was requested).
     */
    @SuppressWarnings("unchecked")
    public List<Object> ftSearchRaw(String indexName, String query, String... extraArgs) {
        return redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<List<Object>>) connection -> {
            StatefulRedisConnection<byte[], byte[]> statefulConn = resolveStatefulConnection(connection.getNativeConnection());

            ByteArrayCodec codec = ByteArrayCodec.INSTANCE;
            CommandArgs<byte[], byte[]> args = new CommandArgs<>(codec);
            args.add(indexName.getBytes(StandardCharsets.UTF_8));
            args.add(query.getBytes(StandardCharsets.UTF_8));
            for (String arg : extraArgs) {
                args.add(arg.getBytes(StandardCharsets.UTF_8));
            }

            NestedMultiOutput<byte[], byte[]> output = new NestedMultiOutput<>(codec);
            List<Object> result = statefulConn.sync().dispatch(FT_SEARCH, output, args);
            return normalizeToResp2Flat(result);
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
     * Returns the same RESP2-flat layout as {@link #ftSearchRaw(String, String, String...)}.
     */
    @SuppressWarnings("unchecked")
    public List<Object> ftSearchWithBinaryArgs(String indexName, byte[][] allArgs) {
        return redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<List<Object>>) connection -> {
            StatefulRedisConnection<byte[], byte[]> statefulConn = resolveStatefulConnection(connection.getNativeConnection());

            ByteArrayCodec codec = ByteArrayCodec.INSTANCE;
            CommandArgs<byte[], byte[]> args = new CommandArgs<>(codec);
            args.add(indexName.getBytes(StandardCharsets.UTF_8));
            for (byte[] arg : allArgs) {
                args.add(arg);
            }

            NestedMultiOutput<byte[], byte[]> output = new NestedMultiOutput<>(codec);
            List<Object> result = statefulConn.sync().dispatch(FT_SEARCH, output, args);
            return normalizeToResp2Flat(result);
        });
    }

    /**
     * Parse a RESP2-flat FT.SEARCH response into a list of maps, one per document.
     * Each map includes the document key under {@code _key} plus every returned field.
     *
     * <p>Assumes the input already uses the flat layout
     * {@code [count, key1, [fields1], key2, [fields2], ...]} produced by
     * {@link #ftSearchRaw(String, String, String...)} and
     * {@link #ftSearchWithBinaryArgs(String, byte[][])}, which normalize RESP3
     * responses upstream so all callers observe the same structure regardless
     * of the negotiated Redis protocol.
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
                for (int j = 0; j + 1 < fields.size(); j += 2) {
                    doc.put(toStr(fields.get(j)), toStr(fields.get(j + 1)));
                }
            }
            results.add(doc);
        }
        return results;
    }

    /**
     * Normalize an FT.SEARCH response to the RESP2-flat layout:
     * <pre>[count, key1, (score1)?, [f1,v1,...], key2, (score2)?, [...], ...]</pre>
     *
     * <p>RESP2 responses are returned unchanged. RESP3 responses — which Lettuce's
     * {@link NestedMultiOutput} delivers as a flat list of alternating key/value
     * pairs for each map level (top-level document and each per-result document) —
     * are converted by locating {@code total_results} and {@code results}, then
     * rewriting each result's {@code id}, optional {@code score} (present only
     * when {@code WITHSCORES} was requested), and fields from
     * {@code extra_attributes} (falling back to {@code values} if missing).
     */
    @SuppressWarnings("unchecked")
    private List<Object> normalizeToResp2Flat(List<Object> raw) {
        if (raw == null || raw.isEmpty()) return raw;
        Object first = raw.get(0);
        // RESP2 already starts with the integer count — nothing to do.
        if (first instanceof Number) return raw;

        long total = 0L;
        List<Object> resultsList = null;
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            String key = toStr(raw.get(i));
            Object val = raw.get(i + 1);
            if ("total_results".equals(key) && val instanceof Number n) {
                total = n.longValue();
            } else if ("results".equals(key) && val instanceof List<?> l) {
                resultsList = (List<Object>) l;
            }
        }

        List<Object> flat = new ArrayList<>();
        flat.add(total);
        if (resultsList == null) return flat;

        for (Object docObj : resultsList) {
            if (!(docObj instanceof List<?> docFlat)) continue;
            String docKey = null;
            String scoreStr = null;
            List<Object> attrs = null;
            List<Object> values = null;
            for (int i = 0; i + 1 < docFlat.size(); i += 2) {
                String fn = toStr(docFlat.get(i));
                Object fv = docFlat.get(i + 1);
                switch (fn) {
                    case "id" -> docKey = toStr(fv);
                    case "score" -> scoreStr = toStr(fv);
                    case "extra_attributes" -> {
                        if (fv instanceof List<?> l) attrs = (List<Object>) l;
                    }
                    case "values" -> {
                        if (fv instanceof List<?> l) values = (List<Object>) l;
                    }
                    default -> { /* ignore payload, sortkey, format, etc. */ }
                }
            }
            if (docKey == null) continue;
            flat.add(docKey);
            if (scoreStr != null) flat.add(scoreStr);
            List<Object> fields = (attrs != null && !attrs.isEmpty())
                    ? attrs
                    : (values != null ? values : new ArrayList<>());
            flat.add(fields);
        }
        return flat;
    }

    public static String toStr(Object obj) {
        if (obj == null) return "";
        if (obj instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (obj instanceof String s) return s;
        return obj.toString();
    }

    /**
     * Convert float[] to little-endian byte array for Redis FLOAT32 vectors.
     */
    public static byte[] vectorToBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }
}
