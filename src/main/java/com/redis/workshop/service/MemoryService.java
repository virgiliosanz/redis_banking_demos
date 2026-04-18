package com.redis.workshop.service;

import com.redis.workshop.config.DocumentDataLoader;
import com.redis.workshop.config.RedisScanHelper;
import com.redis.workshop.config.RedisSearchHelper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
    private static final int VECTOR_DIM = 1536;

    private final StringRedisTemplate redis;
    private final OpenAiService openAiService;
    private final RedisSearchHelper redisSearchHelper;

    private final List<Map<String, String>> memories = new ArrayList<>();

    public MemoryService(StringRedisTemplate redis, OpenAiService openAiService,
                         RedisSearchHelper redisSearchHelper) {
        this.redis = redis;
        this.openAiService = openAiService;
        this.redisSearchHelper = redisSearchHelper;
    }

    @PostConstruct
    public void init() {
        loadLongTermMemories();
        createIndex();
    }

    private void loadLongTermMemories() {
        memories.clear();
        var items = List.of(
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
        memories.addAll(items);

        List<float[]> vectors;
        if (openAiService.isConfigured()) {
            log.info("UC9: Generating real embeddings for {} memories via OpenAI...", items.size());
            List<String> texts = items.stream()
                    .map(m -> m.get("summary") + " " + m.get("tags") + " " + m.get("detail"))
                    .toList();
            try {
                vectors = openAiService.getEmbeddings(texts);
            } catch (OpenAiException e) {
                log.warn("UC9: OpenAI embeddings failed for memories ({}), falling back to mock vectors", e.getMessage());
                vectors = items.stream()
                        .map(m -> DocumentDataLoader.generateVector(m.get("summary") + " " + m.get("tags")))
                        .toList();
            }
        } else {
            vectors = items.stream()
                    .map(m -> DocumentDataLoader.generateVector(m.get("summary") + " " + m.get("tags")))
                    .toList();
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

    private void createIndex() {
        RedisVectorOps.dropIndex(redis, MEMORY_INDEX);
        RedisVectorOps.createVectorIndex(redis, MEMORY_INDEX, MEMORY_PREFIX,
                "summary TEXT tags TAG SEPARATOR , date TEXT", VECTOR_DIM);
    }

    public List<Map<String, String>> listMemories() {
        return memories;
    }

    /** Keyword-based scoring fallback when OpenAI isn't configured. */
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
        return RedisVectorOps.vectorSearch(redisSearchHelper, openAiService, MEMORY_INDEX, query, k);
    }

    public void reset() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, MEMORY_PREFIX + "*");
        if (!keys.isEmpty()) redis.delete(keys);
        init();
    }
}
