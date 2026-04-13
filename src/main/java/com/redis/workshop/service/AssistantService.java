package com.redis.workshop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class AssistantService {

    private static final String CONV_PREFIX = "workshop:assistant:conversation:";
    private static final String MEMORY_PREFIX = "workshop:assistant:memory:";
    private static final String KB_PREFIX = "workshop:assistant:kb:";
    private static final String MEMORY_INDEX = "idx:assistant_memory";
    private static final String KB_INDEX = "idx:assistant_kb";
    private static final int VECTOR_DIM = 768;
    private static final long CONV_TTL_SECONDS = 600;
    private static final int MAX_MESSAGES = 20;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Pre-loaded data for keyword matching
    private final List<Map<String, String>> memories = new ArrayList<>();
    private final List<Map<String, String>> kbArticles = new ArrayList<>();

    public AssistantService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    public void init() {
        loadLongTermMemories();
        loadKnowledgeBase();
        createIndexes();
    }

    // ── Long-Term Memories ──────────────────────────────────────────────
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

        for (var mem : items) {
            String key = MEMORY_PREFIX + mem.get("id");
            float[] vector = generateMockVector(mem.get("summary") + " " + mem.get("tags"));
            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("id", mem.get("id"));
            hash.put("summary", mem.get("summary"));
            hash.put("detail", mem.get("detail"));
            hash.put("date", mem.get("date"));
            hash.put("tags", mem.get("tags"));
            redis.opsForHash().putAll(key, hash);
            storeVectorField(key, vector);
        }
    }

    // ── Knowledge Base ──────────────────────────────────────────────────
    private void loadKnowledgeBase() {
        kbArticles.clear();
        var articles = List.of(
            Map.of("id", "kb-001", "title", "Account Types & Features",
                    "content", "We offer Personal Current, Savings, Business Current, and Premium accounts. Personal Current has no monthly fee, free debit card, and mobile banking. Savings offers 2.1% AER on balances over €1,000. Business Current includes invoicing tools and multi-user access. Premium includes concierge service, travel insurance, and priority support.",
                    "tags", "account,types,savings,business,premium,current"),
            Map.of("id", "kb-002", "title", "International Transfer Limits & Fees",
                    "content", "SEPA transfers: free for amounts under €50,000, €0.50 fee above. Processing time: 1 business day. SWIFT transfers: €35 flat fee, 2-4 business days. Daily limit: €100,000 (Personal), €500,000 (Business). Instant SEPA (SCT Inst): €1 fee, limit €100,000, processed in under 10 seconds.",
                    "tags", "transfer,international,sepa,swift,fees,limits"),
            Map.of("id", "kb-003", "title", "Card Security & Fraud Prevention",
                    "content", "All cards support 3D Secure 2.0 for online purchases. Real-time transaction monitoring flags unusual patterns. Instant card freeze via mobile app. Contactless limit: €50 per transaction, €150 cumulative before PIN required. Virtual cards available for online shopping. Zero liability for unauthorized transactions reported within 48 hours.",
                    "tags", "card,security,fraud,3dsecure,contactless,virtual"),
            Map.of("id", "kb-004", "title", "Investment Products Overview",
                    "content", "Managed portfolios: Conservative (bonds 70%, equities 30%), Balanced (50/50), Growth (equities 80%, bonds 20%). Minimum investment: €5,000. Robo-advisor available for automated rebalancing. ETF marketplace with 500+ funds. No commission on EU-listed ETFs. Custody fee: 0.15% annually.",
                    "tags", "investment,portfolio,etf,managed,robo,bonds,equities"),
            Map.of("id", "kb-005", "title", "Loan & Mortgage Rates",
                    "content", "Personal loans: 5.9% APR (€1,000-€50,000), terms 1-7 years. Mortgage: fixed 2.8% (10yr), 3.1% (20yr), 3.4% (30yr). Variable: Euribor 12M + 0.85%. Green mortgage discount: -0.2% for energy-efficient homes (EPC A/B). Early repayment fee: 0.5% of outstanding balance.",
                    "tags", "loan,mortgage,rates,personal,euribor,green"),
            Map.of("id", "kb-006", "title", "Insurance Products",
                    "content", "Home insurance from €15/month covering fire, theft, water damage. Travel insurance: €4.99/trip or €49/year for unlimited trips. Life insurance: term life from €12/month (€100,000 coverage). Payment protection insurance for loans: covers unemployment and illness. All policies managed via the mobile app.",
                    "tags", "insurance,home,travel,life,payment,protection"),
            Map.of("id", "kb-007", "title", "Open Banking & PSD2 Compliance",
                    "content", "Fully PSD2 compliant with strong customer authentication (SCA). Account Information Service (AIS) API for aggregating accounts from other banks. Payment Initiation Service (PIS) API for third-party payment initiation. Developer portal with sandbox environment. OAuth 2.0 + OpenID Connect for authorization. Rate limit: 4 requests/second per TPP.",
                    "tags", "openbanking,psd2,api,sca,ais,pis,oauth"),
            Map.of("id", "kb-008", "title", "SEPA & Cross-Border Payments",
                    "content", "SEPA Credit Transfer (SCT): 1 business day across 36 countries. SEPA Instant (SCT Inst): under 10 seconds, available 24/7/365. SEPA Direct Debit (SDD): for recurring payments, 14-day refund period. Non-EUR transfers via SWIFT: GBP, USD, CHF, JPY supported. FX markup: 0.3% above mid-market rate.",
                    "tags", "sepa,cross-border,payments,instant,direct-debit,swift,fx"),
            Map.of("id", "kb-009", "title", "Digital Banking Features",
                    "content", "Mobile app: biometric login, instant notifications, spending analytics, budget categories. Online banking: full account management, batch payments, statement download (PDF/CSV/MT940). API access for Business accounts. Multi-currency wallets for EUR, GBP, USD. Scheduled transfers and standing orders.",
                    "tags", "digital,mobile,app,online,banking,api,notifications"),
            Map.of("id", "kb-010", "title", "Regulatory Compliance (MiFID II, GDPR)",
                    "content", "MiFID II: suitability assessments for investment products, cost transparency reports, best execution policy. GDPR: data minimization, right to erasure, data portability, consent management. AML/KYC: identity verification via video call or in-branch, ongoing transaction monitoring, PEP and sanctions screening.",
                    "tags", "compliance,mifid,gdpr,aml,kyc,regulation")
        );
        kbArticles.addAll(articles);

        for (var art : articles) {
            String key = KB_PREFIX + art.get("id");
            float[] vector = generateMockVector(art.get("title") + " " + art.get("tags"));
            Map<String, String> hash = new LinkedHashMap<>();
            hash.put("id", art.get("id"));
            hash.put("title", art.get("title"));
            hash.put("content", art.get("content"));
            hash.put("tags", art.get("tags"));
            redis.opsForHash().putAll(key, hash);
            storeVectorField(key, vector);
        }
    }

    // ── RediSearch Indexes ──────────────────────────────────────────────
    private void createIndexes() {
        // Drop + recreate indexes via RedisCallback
        dropIndex(MEMORY_INDEX);
        dropIndex(KB_INDEX);

        createVectorIndex(MEMORY_INDEX, MEMORY_PREFIX,
                "summary TEXT tags TAG SEPARATOR , date TEXT");
        createVectorIndex(KB_INDEX, KB_PREFIX,
                "title TEXT content TEXT tags TAG SEPARATOR ,");
    }

    private void dropIndex(String indexName) {
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) conn ->
                    conn.execute("FT.DROPINDEX", indexName.getBytes()));
        } catch (Exception ignored) {}
    }

    private void createVectorIndex(String indexName, String prefix, String extraSchemaFields) {
        try {
            // Build the FT.CREATE args list
            // FT.CREATE <idx> ON HASH PREFIX 1 <prefix> SCHEMA <fields...> vector VECTOR HNSW 6 TYPE FLOAT32 DIM 768 DISTANCE_METRIC COSINE
            List<byte[]> argList = new ArrayList<>();
            argList.add(indexName.getBytes());
            argList.add("ON".getBytes());
            argList.add("HASH".getBytes());
            argList.add("PREFIX".getBytes());
            argList.add("1".getBytes());
            argList.add(prefix.getBytes());
            argList.add("SCHEMA".getBytes());
            // Split extra schema field definitions
            for (String part : extraSchemaFields.split("\\s+")) {
                argList.add(part.getBytes());
            }
            // Vector field
            argList.add("vector".getBytes());
            argList.add("VECTOR".getBytes());
            argList.add("HNSW".getBytes());
            argList.add("6".getBytes());
            argList.add("TYPE".getBytes());
            argList.add("FLOAT32".getBytes());
            argList.add("DIM".getBytes());
            argList.add(String.valueOf(VECTOR_DIM).getBytes());
            argList.add("DISTANCE_METRIC".getBytes());
            argList.add("COSINE".getBytes());

            byte[][] args = argList.toArray(new byte[0][]);
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) conn ->
                    conn.execute("FT.CREATE", args));
        } catch (Exception e) {
            System.out.println("Index creation note (" + indexName + "): " + e.getMessage());
        }
    }

    // ── Chat Endpoint ───────────────────────────────────────────────────
    public Map<String, Object> chat(String sessionId, String userName, String userMessage) {
        long startTime = System.currentTimeMillis();
        String convKey = CONV_PREFIX + sessionId;

        // 1. Load or create conversation (short-term memory)
        Map<Object, Object> convData = redis.opsForHash().entries(convKey);
        List<Map<String, String>> messageHistory;
        if (convData.isEmpty()) {
            messageHistory = new ArrayList<>();
            redis.opsForHash().put(convKey, "created_at", Instant.now().toString());
            redis.opsForHash().put(convKey, "user_name", userName);
        } else {
            messageHistory = parseMessages(convData.getOrDefault("messages", "[]").toString());
        }

        // 2. Append user message
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        userMsg.put("timestamp", Instant.now().toString());
        messageHistory.add(userMsg);

        // Trim to last N messages
        if (messageHistory.size() > MAX_MESSAGES) {
            messageHistory = new ArrayList<>(messageHistory.subList(
                    messageHistory.size() - MAX_MESSAGES, messageHistory.size()));
        }

        // 3. Retrieve relevant long-term memories (keyword-based mock)
        List<Map<String, String>> relevantMemories = findRelevantMemories(userMessage);

        // 4. Retrieve relevant KB documents (keyword-based mock)
        List<Map<String, String>> relevantDocs = findRelevantKBDocs(userMessage);

        // 5. Generate mock response
        String responseText = generateResponse(userMessage, relevantMemories, relevantDocs);

        // 6. Append assistant message
        Map<String, String> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", responseText);
        assistantMsg.put("timestamp", Instant.now().toString());
        messageHistory.add(assistantMsg);

        // Trim again after adding response
        if (messageHistory.size() > MAX_MESSAGES) {
            messageHistory = new ArrayList<>(messageHistory.subList(
                    messageHistory.size() - MAX_MESSAGES, messageHistory.size()));
        }

        // 7. Store updated conversation — HSET with TTL
        try {
            String messagesJson = objectMapper.writeValueAsString(messageHistory);
            redis.opsForHash().put(convKey, "messages", messagesJson);
            redis.opsForHash().put(convKey, "last_active", Instant.now().toString());
            redis.opsForHash().put(convKey, "user_name", userName);
            redis.expire(convKey, CONV_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize messages", e);
        }

        long latencyMs = System.currentTimeMillis() - startTime;

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("response", responseText);
        result.put("memoriesRetrieved", relevantMemories);
        result.put("kbDocsRetrieved", relevantDocs);
        result.put("conversationLength", messageHistory.size());
        result.put("latencyMs", latencyMs);
        result.put("redisCommands", List.of(
                "HGETALL " + convKey,
                "HSET " + convKey + " messages <JSON array>",
                "HSET " + convKey + " last_active " + Instant.now(),
                "EXPIRE " + convKey + " " + CONV_TTL_SECONDS,
                "FT.SEARCH " + MEMORY_INDEX + " \"*=>[KNN 3 @vector $BLOB]\"",
                "FT.SEARCH " + KB_INDEX + " \"*=>[KNN 3 @vector $BLOB]\""
        ));
        return result;
    }

    // ── Conversation Inspection ─────────────────────────────────────────
    public Map<String, Object> getConversation(String sessionId) {
        String convKey = CONV_PREFIX + sessionId;
        Map<Object, Object> data = redis.opsForHash().entries(convKey);
        if (data.isEmpty()) return Map.of("exists", false);

        Long ttl = redis.getExpire(convKey, TimeUnit.SECONDS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("sessionId", sessionId);
        result.put("redisKey", convKey);
        result.put("userName", data.getOrDefault("user_name", ""));
        result.put("createdAt", data.getOrDefault("created_at", ""));
        result.put("lastActive", data.getOrDefault("last_active", ""));
        result.put("ttl", ttl != null ? ttl : -1);
        result.put("messages", parseMessages(data.getOrDefault("messages", "[]").toString()));
        return result;
    }

    // ── Memory & KB listing for inspection panel ────────────────────────
    public List<Map<String, String>> listMemories() {
        return memories;
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

    // ── Keyword-based retrieval (mock for vector search) ────────────────
    private List<Map<String, String>> findRelevantMemories(String query) {
        String lower = query.toLowerCase();
        List<Map<String, String>> results = new ArrayList<>();
        for (var mem : memories) {
            String searchable = (mem.get("summary") + " " + mem.get("tags") + " " + mem.get("detail")).toLowerCase();
            int score = calculateKeywordScore(lower, searchable, mem.get("tags").toLowerCase());
            if (score > 0) {
                Map<String, String> match = new LinkedHashMap<>(mem);
                match.put("score", String.valueOf(score));
                results.add(match);
            }
        }
        results.sort((a, b) -> Integer.compare(Integer.parseInt(b.get("score")), Integer.parseInt(a.get("score"))));
        return results.size() > 3 ? results.subList(0, 3) : results;
    }

    private List<Map<String, String>> findRelevantKBDocs(String query) {
        String lower = query.toLowerCase();
        List<Map<String, String>> results = new ArrayList<>();
        for (var art : kbArticles) {
            String searchable = (art.get("title") + " " + art.get("tags") + " " + art.get("content")).toLowerCase();
            int score = calculateKeywordScore(lower, searchable, art.get("tags").toLowerCase());
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

    private int calculateKeywordScore(String query, String document, String tags) {
        int score = 0;
        String[] keywords = query.split("\\s+");
        for (String kw : keywords) {
            if (kw.length() < 3) continue; // skip short words
            if (tags.contains(kw)) score += 3;
            if (document.contains(kw)) score += 1;
        }
        return score;
    }

    // ── Mock Response Generation ────────────────────────────────────────
    private String generateResponse(String query, List<Map<String, String>> memories,
                                     List<Map<String, String>> kbDocs) {
        StringBuilder sb = new StringBuilder();

        // If we have relevant KB docs, use their content
        if (!kbDocs.isEmpty()) {
            sb.append("Based on our banking knowledge base, here's what I found:\n\n");
            for (var doc : kbDocs) {
                sb.append("📄 **").append(doc.get("title")).append("**: ");
                sb.append(doc.get("content")).append("\n\n");
            }
        }

        // If we have relevant memories, reference them
        if (!memories.isEmpty()) {
            sb.append("I also found relevant context from your previous interactions:\n\n");
            for (var mem : memories) {
                sb.append("🧠 *").append(mem.get("summary")).append("* (").append(mem.get("date")).append("): ");
                sb.append(mem.get("detail")).append("\n\n");
            }
        }

        // Fallback if nothing was found
        if (sb.length() == 0) {
            sb.append(getDefaultResponse(query));
        }

        return sb.toString().trim();
    }

    private String getDefaultResponse(String query) {
        String lower = query.toLowerCase();
        if (lower.contains("hello") || lower.contains("hi") || lower.contains("hey")) {
            return "Hello! I'm your AI Banking Assistant. I can help you with account information, transfers, loans, investments, and more. What would you like to know?";
        }
        if (lower.contains("help")) {
            return "I can assist you with:\n• Account types and features\n• Transfer limits and fees (SEPA, SWIFT)\n• Card security and fraud prevention\n• Investment products\n• Loan and mortgage rates\n• Insurance products\n• Open Banking and PSD2\n\nWhat topic interests you?";
        }
        if (lower.contains("thank")) {
            return "You're welcome! Is there anything else I can help you with today?";
        }
        return "I understand you're asking about \"" + query + "\". Let me look into that for you. Could you provide more details? You can ask about transfers, accounts, investments, loans, cards, insurance, or regulations.";
    }

    // ── Reset ───────────────────────────────────────────────────────────
    public void reset() {
        // Clean up conversation keys
        Set<String> convKeys = redis.keys(CONV_PREFIX + "*");
        if (convKeys != null && !convKeys.isEmpty()) redis.delete(convKeys);
        Set<String> memKeys = redis.keys(MEMORY_PREFIX + "*");
        if (memKeys != null && !memKeys.isEmpty()) redis.delete(memKeys);
        Set<String> kbKeys = redis.keys(KB_PREFIX + "*");
        if (kbKeys != null && !kbKeys.isEmpty()) redis.delete(kbKeys);
        // Reload data
        init();
    }

    // ── Utility Methods ─────────────────────────────────────────────────
    private List<Map<String, String>> parseMessages(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Generate a deterministic pseudo-random 768-dim vector from content hash.
     * Uses the content string's hash as a seed for reproducibility.
     */
    private float[] generateMockVector(String content) {
        float[] vector = new float[VECTOR_DIM];
        long seed = content.hashCode();
        Random rng = new Random(seed);
        double norm = 0;
        for (int i = 0; i < VECTOR_DIM; i++) {
            vector[i] = (float) (rng.nextGaussian());
            norm += vector[i] * vector[i];
        }
        // Normalize to unit vector
        norm = Math.sqrt(norm);
        for (int i = 0; i < VECTOR_DIM; i++) {
            vector[i] /= (float) norm;
        }
        return vector;
    }

    /**
     * Store vector field as raw bytes using RedisCallback (required for HNSW indexing).
     */
    private void storeVectorField(String key, float[] vector) {
        byte[] keyBytes = key.getBytes();
        byte[] vectorBytes = vectorToBytes(vector);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) conn -> {
            conn.hashCommands().hSet(keyBytes, "vector".getBytes(), vectorBytes);
            return null;
        });
    }

    /**
     * Convert float[] to little-endian byte array for Redis FLOAT32 vectors.
     */
    private byte[] vectorToBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }
}