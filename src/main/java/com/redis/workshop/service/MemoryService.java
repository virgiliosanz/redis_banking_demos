package com.redis.workshop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.config.RedisScanHelper;
import com.redis.workshop.config.RedisSearchHelper;
import com.redis.workshop.config.RedisStartupHelper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.util.*;

/**
 * Long-term memory management for UC9.
 * Loads preset banking interaction memories, stores them as Redis hashes with vector
 * embeddings, and exposes keyword + KNN retrieval APIs.
 */
@Service
@DependsOn("startupCleanup")
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final String MEMORY_PREFIX = "uc9:memory:";
    private static final String MEMORY_INDEX = "idx:uc9:memory";
    private static final int VECTOR_DIM = 384;
    private static final int MEMORY_SEED_COUNT = 6;
    private static final String EMBEDDINGS_RESOURCE = "/data/uc9-memory-embeddings.json";
    private static final String EMBEDDINGS_WRITE_PATH = "src/main/resources/data/uc9-memory-embeddings.json";

    private final StringRedisTemplate redis;
    private final LocalEmbeddingService localEmbeddingService;
    private final RedisSearchHelper redisSearchHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${workshop.startup.load-data:true}")
    private boolean loadData;

    @Value("${workshop.startup.force-reload:false}")
    private boolean forceReload;

    private final List<Map<String, String>> memories = new ArrayList<>();

    public MemoryService(StringRedisTemplate redis, LocalEmbeddingService localEmbeddingService,
                         RedisSearchHelper redisSearchHelper) {
        this.redis = redis;
        this.localEmbeddingService = localEmbeddingService;
        this.redisSearchHelper = redisSearchHelper;
    }

    @PostConstruct
    public void init() {
        if (!loadData) return;
        List<Map<String, String>> items = buildLongTermMemories();
        memories.clear();
        memories.addAll(items);

        if (forceReload) {
            log.info("UC9: force reload enabled for memory index, rebuilding {} memories", items.size());
        } else {
            long existingDocs = existingMemoryDocCount();
            if (existingDocs >= MEMORY_SEED_COUNT) {
                int existingVectorDim = existingMemoryVectorDimension();
                if (existingVectorDim == VECTOR_DIM) {
                    log.info("UC9: Memory index already present ({} docs, {}-dim vectors), skipping reload",
                            existingDocs, existingVectorDim);
                    return;
                }
                log.info("UC9: Memory data present but vector dimension is {} (expected {}), rebuilding",
                        existingVectorDim, VECTOR_DIM);
            }
        }

        loadLongTermMemories(items);
        createIndex();
    }

    private List<Map<String, String>> buildLongTermMemories() {
        return List.of(
            Map.of("id", "mem-001", "summary", "Asked about international wire transfer fees",
                    "detail", "Customer inquired about SWIFT transfer costs to the UK. Quoted €15 flat fee for SEPA, €35 for SWIFT. Recommended SEPA for EU destinations.",
                    "date", "2024-03-15", "tags", "transfer,international,fees,swift,sepa"),
            Map.of("id", "mem-002", "summary", "Inquired about mortgage refinancing options",
                    "detail", "Customer explored refinancing a 25-year fixed mortgage at 3.2%. Discussed variable rate options at Euribor+0.9%. Sent comparison PDF.",
                    "date", "2024-02-28", "tags", "mortgage,refinancing,rates,euribor"),
            Map.of("id", "mem-003", "summary", "Requested information on investment portfolio diversification",
                    "detail", "Customer asked about diversifying beyond equities. Suggested bond ETFs, real estate REITs, and commodity exposure. Risk profile: moderate.",
                    "date", "2024-03-01", "tags", "investment,portfolio,diversification,etf,bonds"),
            Map.of("id", "mem-004", "summary", "Asked about SEPA payment regulations post-Brexit",
                    "detail", "Customer concerned about SEPA transfers to UK after Brexit. Explained UK left SEPA but GBP transfers still possible via SWIFT. SEPA only for EUR in EEA.",
                    "date", "2024-03-10", "tags", "sepa,brexit,uk,regulations,payment"),
            Map.of("id", "mem-005", "summary", "Discussed credit card fraud protection measures",
                    "detail", "Customer reported suspicious activity. Enabled 3D Secure, set transaction alerts, reviewed chargeback process. Card temporarily blocked and reissued.",
                    "date", "2024-03-12", "tags", "credit,card,fraud,security,3dsecure"),
            Map.of("id", "mem-006", "summary", "Asked about opening a business account for startup",
                    "detail", "Customer starting a fintech company, needed business current account with API access. Recommended Business Pro plan with Open Banking APIs.",
                    "date", "2024-01-20", "tags", "business,account,startup,api,openbanking")
        );
    }

    private void loadLongTermMemories(List<Map<String, String>> items) {
        List<float[]> vectors = loadPrecomputedVectors(items);
        if (vectors != null) {
            log.info("UC9: Loaded {} memory embeddings from {}", vectors.size(), EMBEDDINGS_RESOURCE);
        } else {
            log.info("UC9: Generating local BGE embeddings for {} memories...", items.size());
            List<String> texts = items.stream()
                    .map(this::toEmbeddingText)
                    .toList();
            vectors = localEmbeddingService.getEmbeddings(texts);
            tryWriteEmbeddings(items, vectors);
        }

        for (int i = 0; i < items.size(); i++) {
            var mem = items.get(i);
            String key = MEMORY_PREFIX + mem.get("id");
            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("id", mem.get("id"));
            hash.put("summary", mem.get("summary"));
            hash.put("detail", mem.get("detail"));
            hash.put("date", mem.get("date"));
            hash.put("tags", mem.get("tags"));
            redis.opsForHash().putAll(key, hash);
            RedisVectorOps.storeVectorField(redis, key, vectors.get(i));
        }
    }

    private List<float[]> loadPrecomputedVectors(List<Map<String, String>> items) {
        try (InputStream is = getClass().getResourceAsStream(EMBEDDINGS_RESOURCE)) {
            if (is == null) {
                return null;
            }
            List<Map<String, Object>> storedEntries = objectMapper.readValue(is, new TypeReference<>() {});
            if (storedEntries == null || storedEntries.size() != items.size()) {
                return null;
            }

            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> entry : storedEntries) {
                byId.put(String.valueOf(entry.get("id")), entry);
            }

            List<float[]> vectors = new ArrayList<>();
            for (Map<String, String> item : items) {
                Map<String, Object> entry = byId.get(item.get("id"));
                if (entry == null) {
                    return null;
                }
                float[] vector = vectorFromObject(entry.get("vector"));
                if (vector == null || vector.length != VECTOR_DIM) {
                    return null;
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (Exception e) {
            log.warn("UC9: Failed to load pre-computed memory embeddings: {}", e.getMessage());
            return null;
        }
    }

    private void tryWriteEmbeddings(List<Map<String, String>> items, List<float[]> vectors) {
        try {
            File target = new File(EMBEDDINGS_WRITE_PATH);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            if (parent == null || !parent.isDirectory()) {
                log.debug("UC9: Skipping memory embeddings write ({} not available)", EMBEDDINGS_WRITE_PATH);
                return;
            }

            List<Map<String, Object>> payload = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Map<String, String> item = items.get(i);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", item.get("id"));
                entry.put("summary", item.get("summary"));
                entry.put("detail", item.get("detail"));
                entry.put("tags", item.get("tags"));
                entry.put("date", item.get("date"));
                entry.put("vector", vectors.get(i));
                payload.add(entry);
            }

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target, payload);
            log.info("UC9: Saved memory embeddings to {}", target.getAbsolutePath());
        } catch (Exception e) {
            log.warn("UC9: Failed to write memory embeddings JSON: {}", e.getMessage());
        }
    }

    private float[] vectorFromObject(Object vectorObj) {
        if (vectorObj instanceof float[] arr) {
            return arr;
        }
        if (vectorObj instanceof List<?> vectorList) {
            float[] vector = new float[vectorList.size()];
            for (int i = 0; i < vectorList.size(); i++) {
                vector[i] = ((Number) vectorList.get(i)).floatValue();
            }
            return vector;
        }
        return null;
    }

    private String toEmbeddingText(Map<String, String> memory) {
        return (memory.getOrDefault("summary", "") + " "
                + memory.getOrDefault("detail", "") + " "
                + memory.getOrDefault("tags", "")).trim();
    }

    private long existingMemoryDocCount() {
        try {
            return Math.max(
                    RedisStartupHelper.indexDocCount(redis, MEMORY_INDEX),
                    RedisStartupHelper.countKeys(redis, MEMORY_PREFIX + "*")
            );
        } catch (Exception e) {
            return RedisStartupHelper.countKeys(redis, MEMORY_PREFIX + "*");
        }
    }

    private int existingMemoryVectorDimension() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, MEMORY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return -1;
        }
        return RedisStartupHelper.hashVectorDimension(redis, keys.iterator().next());
    }

    private void createIndex() {
        RedisVectorOps.dropIndex(redis, MEMORY_INDEX);
        RedisVectorOps.createVectorIndex(redis, MEMORY_INDEX, MEMORY_PREFIX,
                "summary TEXT tags TAG SEPARATOR , date TEXT", VECTOR_DIM);
    }

    public List<Map<String, String>> listMemories() {
        return memories;
    }

    /** Keyword-based retrieval used for demo context previews. */
    public List<Map<String, String>> findRelevantMemories(String query) {
        String lower = query.toLowerCase();
        List<Map<String, String>> results = new ArrayList<>();
        for (var mem : memories) {
            String searchable = (mem.get("summary") + " " + mem.get("tags") + " " + mem.get("detail")).toLowerCase();
            int score = RedisVectorOps.keywordScore(lower, searchable, mem.get("tags").toLowerCase());
            if (score > 0) {
                Map<String, String> match = new LinkedHashMap<>(mem);
                match.put("score", String.valueOf(score));
                results.add(match);
            }
        }
        results.sort((a, b) -> Integer.compare(Integer.parseInt(b.get("score")), Integer.parseInt(a.get("score"))));
        return results.size() > 3 ? results.subList(0, 3) : results;
    }

    /** KNN vector search against the memory index. */
    public List<Map<String, Object>> vectorSearchMemories(String query, int k) {
        return RedisVectorOps.vectorSearch(redisSearchHelper, localEmbeddingService, MEMORY_INDEX, query, k);
    }

    public void reset() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, MEMORY_PREFIX + "*");
        if (!keys.isEmpty()) redis.delete(keys);
        init();
    }
}
