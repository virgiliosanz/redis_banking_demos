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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Knowledge base articles and regulation document retrieval for UC9.
 * Owns the UC9 KB index and also queries UC8's regulation document index for RAG.
 */
@Service
@DependsOn("startupCleanup")
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private static final String KB_PREFIX = "uc9:kb:";
    private static final String KB_INDEX = "idx:uc9:kb";
    // UC8 regulation documents (PDF chunks) — reused for RAG
    private static final String DOC_INDEX = "idx:uc8:documents";
    private static final String DOC_PREFIX = "uc8:doc:";
    private static final int VECTOR_DIM = 384;
    private static final int KB_SEED_COUNT = 10;
    private static final String EMBEDDINGS_RESOURCE = "/data/uc9-kb-embeddings.json";
    private static final String EMBEDDINGS_WRITE_PATH = "src/main/resources/data/uc9-kb-embeddings.json";

    private final StringRedisTemplate redis;
    private final LocalEmbeddingService localEmbeddingService;
    private final RedisSearchHelper redisSearchHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${workshop.startup.load-data:true}")
    private boolean loadData;

    @Value("${workshop.startup.force-reload:false}")
    private boolean forceReload;

    private final List<Map<String, String>> kbArticles = new ArrayList<>();
    private final AtomicBoolean keywordFallbackWarningLogged = new AtomicBoolean(false);

    public KnowledgeBaseService(StringRedisTemplate redis, LocalEmbeddingService localEmbeddingService,
                                RedisSearchHelper redisSearchHelper) {
        this.redis = redis;
        this.localEmbeddingService = localEmbeddingService;
        this.redisSearchHelper = redisSearchHelper;
    }

    @PostConstruct
    public void init() {
        if (!loadData) return;
        List<Map<String, String>> articles = buildKnowledgeBaseArticles();
        kbArticles.clear();
        kbArticles.addAll(articles);

        if (forceReload) {
            log.info("UC9: force reload enabled for KB index, rebuilding {} articles", articles.size());
        } else {
            long existingDocs = existingKbDocCount();
            if (existingDocs >= KB_SEED_COUNT) {
                int existingVectorDim = existingKbVectorDimension();
                if (existingVectorDim == VECTOR_DIM) {
                    log.info("UC9: KB index already present ({} docs, {}-dim vectors), skipping reload",
                            existingDocs, existingVectorDim);
                    return;
                }
                log.info("UC9: KB data present but vector dimension is {} (expected {}), rebuilding",
                        existingVectorDim, VECTOR_DIM);
            }
        }

        loadKnowledgeBase(articles);
        createIndex();
    }

    private List<Map<String, String>> buildKnowledgeBaseArticles() {
        return List.of(
            Map.of("id", "kb-001", "title", "Account Types & Features",
                    "content", "We offer Personal Current, Savings, Business Current, and Premium accounts. Personal Current has no monthly fee, free debit card, and mobile banking. Savings offers 2.1% AER on balances over €1,000. Business Current includes invoicing tools and multi-user access. Premium includes concierge service, travel insurance, and priority support.",
                    "tags", "account,types,savings,business,premium,current",
                    "source", "Product Catalogue 2024"),
            Map.of("id", "kb-002", "title", "International Transfer Limits & Fees",
                    "content", "SEPA transfers: free for amounts under €50,000, €0.50 fee above. Processing time: 1 business day. SWIFT transfers: €35 flat fee, 2-4 business days. Daily limit: €100,000 (Personal), €500,000 (Business). Instant SEPA (SCT Inst): €1 fee, limit €100,000, processed in under 10 seconds.",
                    "tags", "transfer,international,sepa,swift,fees,limits",
                    "source", "SEPA Regulation Guide v3.2"),
            Map.of("id", "kb-003", "title", "Card Security & Fraud Prevention",
                    "content", "All cards support 3D Secure 2.0 for online purchases. Real-time transaction monitoring flags unusual patterns. Instant card freeze via mobile app. Contactless limit: €50 per transaction, €150 cumulative before PIN required. Virtual cards available for online shopping. Zero liability for unauthorized transactions reported within 48 hours.",
                    "tags", "card,security,fraud,3dsecure,contactless,virtual",
                    "source", "Security Policy Handbook"),
            Map.of("id", "kb-004", "title", "Investment Products Overview",
                    "content", "Managed portfolios: Conservative (bonds 70%, equities 30%), Balanced (50/50), Growth (equities 80%, bonds 20%). Minimum investment: €5,000. Robo-advisor available for automated rebalancing. ETF marketplace with 500+ funds. No commission on EU-listed ETFs. Custody fee: 0.15% annually.",
                    "tags", "investment,portfolio,etf,managed,robo,bonds,equities",
                    "source", "MiFID II Product Sheet"),
            Map.of("id", "kb-005", "title", "Loan & Mortgage Rates",
                    "content", "Personal loans: 5.9% APR (€1,000-€50,000), terms 1-7 years. Mortgage: fixed 2.8% (10yr), 3.1% (20yr), 3.4% (30yr). Variable: Euribor 12M + 0.85%. Green mortgage discount: -0.2% for energy-efficient homes (EPC A/B). Early repayment fee: 0.5% of outstanding balance.",
                    "tags", "loan,mortgage,rates,personal,euribor,green",
                    "source", "Lending Terms Q1 2025"),
            Map.of("id", "kb-006", "title", "Insurance Products",
                    "content", "Home insurance from €15/month covering fire, theft, water damage. Travel insurance: €4.99/trip or €49/year for unlimited trips. Life insurance: term life from €12/month (€100,000 coverage). Payment protection insurance for loans: covers unemployment and illness. All policies managed via the mobile app.",
                    "tags", "insurance,home,travel,life,payment,protection",
                    "source", "Insurance Partner Brochure"),
            Map.of("id", "kb-007", "title", "Open Banking & PSD2 Compliance",
                    "content", "Fully PSD2 compliant with strong customer authentication (SCA). Account Information Service (AIS) API for aggregating accounts from other banks. Payment Initiation Service (PIS) API for third-party payment initiation. Developer portal with sandbox environment. OAuth 2.0 + OpenID Connect for authorization. Rate limit: 4 requests/second per TPP.",
                    "tags", "openbanking,psd2,api,sca,ais,pis,oauth",
                    "source", "PSD2 Technical Standards"),
            Map.of("id", "kb-008", "title", "SEPA & Cross-Border Payments",
                    "content", "SEPA Credit Transfer (SCT): 1 business day across 36 countries. SEPA Instant (SCT Inst): under 10 seconds, available 24/7/365. SEPA Direct Debit (SDD): for recurring payments, 14-day refund period. Non-EUR transfers via SWIFT: GBP, USD, CHF, JPY supported. FX markup: 0.3% above mid-market rate.",
                    "tags", "sepa,cross-border,payments,instant,direct-debit,swift,fx",
                    "source", "EPC SEPA Rulebook 2024"),
            Map.of("id", "kb-009", "title", "Digital Banking Features",
                    "content", "Mobile app: biometric login, instant notifications, spending analytics, budget categories. Online banking: full account management, batch payments, statement download (PDF/CSV/MT940). API access for Business accounts. Multi-currency wallets for EUR, GBP, USD. Scheduled transfers and standing orders.",
                    "tags", "digital,mobile,app,online,banking,api,notifications",
                    "source", "Digital Strategy Roadmap"),
            Map.of("id", "kb-010", "title", "Regulatory Compliance (MiFID II, GDPR)",
                    "content", "MiFID II: suitability assessments for investment products, cost transparency reports, best execution policy. GDPR: data minimization, right to erasure, data portability, consent management. AML/KYC: identity verification via video call or in-branch, ongoing transaction monitoring, PEP and sanctions screening.",
                    "tags", "compliance,mifid,gdpr,aml,kyc,regulation",
                    "source", "Compliance Manual v5.1")
        );
    }

    private void loadKnowledgeBase(List<Map<String, String>> articles) {
        List<float[]> vectors = loadPrecomputedVectors(articles);
        if (vectors != null) {
            log.info("UC9: Loaded {} KB embeddings from {}", vectors.size(), EMBEDDINGS_RESOURCE);
        } else {
            log.info("UC9: Generating local BGE embeddings for {} KB articles...", articles.size());
            List<String> texts = articles.stream()
                    .map(this::toEmbeddingText)
                    .toList();
            vectors = localEmbeddingService.getEmbeddings(texts);
            tryWriteEmbeddings(articles, vectors);
        }

        for (int i = 0; i < articles.size(); i++) {
            var art = articles.get(i);
            String key = KB_PREFIX + art.get("id");
            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("id", art.get("id"));
            hash.put("title", art.get("title"));
            hash.put("content", art.get("content"));
            hash.put("tags", art.get("tags"));
            hash.put("source", art.get("source"));
            redis.opsForHash().putAll(key, hash);
            RedisVectorOps.storeVectorField(redis, key, vectors.get(i));
        }
    }

    private List<float[]> loadPrecomputedVectors(List<Map<String, String>> articles) {
        try (InputStream is = getClass().getResourceAsStream(EMBEDDINGS_RESOURCE)) {
            if (is == null) {
                return null;
            }
            List<Map<String, Object>> storedEntries = objectMapper.readValue(is, new TypeReference<>() {});
            if (storedEntries == null || storedEntries.size() != articles.size()) {
                return null;
            }

            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> entry : storedEntries) {
                byId.put(String.valueOf(entry.get("id")), entry);
            }

            List<float[]> vectors = new ArrayList<>();
            for (Map<String, String> article : articles) {
                Map<String, Object> entry = byId.get(article.get("id"));
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
            log.warn("UC9: Failed to load pre-computed KB embeddings: {}", e.getMessage());
            return null;
        }
    }

    private void tryWriteEmbeddings(List<Map<String, String>> articles, List<float[]> vectors) {
        try {
            File target = new File(EMBEDDINGS_WRITE_PATH);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            if (parent == null || !parent.isDirectory()) {
                log.debug("UC9: Skipping KB embeddings write ({} not available)", EMBEDDINGS_WRITE_PATH);
                return;
            }

            List<Map<String, Object>> payload = new ArrayList<>();
            for (int i = 0; i < articles.size(); i++) {
                Map<String, String> article = articles.get(i);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", article.get("id"));
                entry.put("title", article.get("title"));
                entry.put("content", article.get("content"));
                entry.put("tags", article.get("tags"));
                entry.put("source", article.get("source"));
                entry.put("vector", vectors.get(i));
                payload.add(entry);
            }

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target, payload);
            log.info("UC9: Saved KB embeddings to {}", target.getAbsolutePath());
        } catch (Exception e) {
            log.warn("UC9: Failed to write KB embeddings JSON: {}", e.getMessage());
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

    private String toEmbeddingText(Map<String, String> article) {
        return (article.getOrDefault("title", "") + " "
                + article.getOrDefault("tags", "") + " "
                + article.getOrDefault("content", "")).trim();
    }

    private long existingKbDocCount() {
        try {
            return Math.max(
                    RedisStartupHelper.indexDocCount(redis, KB_INDEX),
                    RedisStartupHelper.countKeys(redis, KB_PREFIX + "*")
            );
        } catch (Exception e) {
            return RedisStartupHelper.countKeys(redis, KB_PREFIX + "*");
        }
    }

    private int existingKbVectorDimension() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, KB_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return -1;
        }
        return RedisStartupHelper.hashVectorDimension(redis, keys.iterator().next());
    }

    private void createIndex() {
        RedisVectorOps.dropIndex(redis, KB_INDEX);
        RedisVectorOps.createVectorIndex(redis, KB_INDEX, KB_PREFIX,
                "title TEXT content TEXT tags TAG SEPARATOR ,", VECTOR_DIM);
    }

    public List<Map<String, String>> listKBArticles() {
        List<Map<String, String>> result = new ArrayList<>();
        for (var art : kbArticles) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", art.get("id"));
            item.put("title", art.get("title"));
            item.put("tags", art.get("tags"));
            result.add(item);
        }
        return result;
    }

    public Map<String, String> getKBArticle(String id) {
        for (var art : kbArticles) {
            if (art.get("id").equals(id)) {
                Map<String, String> result = new LinkedHashMap<>();
                result.put("id", art.get("id"));
                result.put("title", art.get("title"));
                result.put("content", art.get("content"));
                result.put("tags", art.get("tags"));
                result.put("source", art.get("source"));
                return result;
            }
        }
        return null;
    }

    public List<Map<String, String>> findRelevantKBDocs(String query) {
        if (!loadData && kbArticles.isEmpty()
                && keywordFallbackWarningLogged.compareAndSet(false, true)) {
            log.warn("UC9: Keyword KB fallback is unavailable because startup data loading was disabled");
        }
        String lower = query.toLowerCase();
        List<Map<String, String>> results = new ArrayList<>();
        for (var art : kbArticles) {
            String searchable = (art.get("title") + " " + art.get("tags") + " " + art.get("content")).toLowerCase();
            int score = RedisVectorOps.keywordScore(lower, searchable, art.get("tags").toLowerCase());
            if (score > 0) {
                Map<String, String> match = new LinkedHashMap<>();
                match.put("id", art.get("id"));
                match.put("title", art.get("title"));
                match.put("content", art.get("content").length() > 150 ?
                        art.get("content").substring(0, 150) + "..." : art.get("content"));
                match.put("tags", art.get("tags"));
                match.put("score", String.valueOf(score));
                results.add(match);
            }
        }
        results.sort((a, b) -> Integer.compare(Integer.parseInt(b.get("score")), Integer.parseInt(a.get("score"))));
        return results.size() > 3 ? results.subList(0, 3) : results;
    }

    public List<Map<String, Object>> vectorSearchKB(String query, int k) {
        return RedisVectorOps.vectorSearch(redisSearchHelper, localEmbeddingService, KB_INDEX, query, k);
    }

    /**
     * KNN search against UC8's regulation document index (idx:uc8:documents).
     * Documents are JSON-backed PDF chunks with fields: title, category, summary, content, tags.
     */
    public List<Map<String, Object>> vectorSearchRegulationDocs(String query, int k) {
        try {
            float[] queryVector = localEmbeddingService.getEmbedding(query);
            byte[] vectorBytes = RedisSearchHelper.vectorToBytes(queryVector);

            String knnQuery = "*=>[KNN " + k + " @vector $BLOB]";
            byte[][] binaryArgs = new byte[][] {
                    knnQuery.getBytes(),
                    "RETURN".getBytes(),
                    "5".getBytes(),
                    "title".getBytes(),
                    "category".getBytes(),
                    "summary".getBytes(),
                    "content".getBytes(),
                    "__vector_score".getBytes(),
                    "PARAMS".getBytes(),
                    "2".getBytes(),
                    "BLOB".getBytes(),
                    vectorBytes,
                    "DIALECT".getBytes(),
                    "2".getBytes()
            };

            List<Object> rawResult = redisSearchHelper.ftSearchWithBinaryArgs(DOC_INDEX, binaryArgs);
            List<Map<String, String>> parsed = redisSearchHelper.parseSearchResults(rawResult);

            List<Map<String, Object>> results = new ArrayList<>();
            for (var doc : parsed) {
                Map<String, Object> entry = new LinkedHashMap<>();
                String key = doc.get("_key");
                entry.put("redisKey", key);
                if (key != null && key.startsWith(DOC_PREFIX)) {
                    entry.put("id", key.substring(DOC_PREFIX.length()));
                } else {
                    entry.put("id", key != null ? key : "");
                }
                entry.put("score", RedisVectorOps.distanceToSimilarity(doc.getOrDefault("__vector_score", "1.0")));
                entry.put("title", doc.getOrDefault("title", ""));
                entry.put("content", doc.getOrDefault("content", ""));
                if (doc.containsKey("summary")) entry.put("summary", doc.get("summary"));
                if (doc.containsKey("category")) {
                    entry.put("category", doc.get("category"));
                    entry.put("tags", doc.get("category"));
                }
                entry.put("docType", "regulation");
                results.add(entry);
            }
            RedisVectorOps.sortByScoreDescending(results);
            return results;
        } catch (Exception e) {
            log.warn("UC9: Regulation doc search failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void reset() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, KB_PREFIX + "*");
        if (!keys.isEmpty()) redis.delete(keys);
        init();
    }
}
