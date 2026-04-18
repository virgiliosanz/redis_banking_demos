package com.redis.workshop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.config.RedisScanHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** UC9 orchestrator: coordinates conversation state + MemoryService / KnowledgeBaseService / SemanticCacheService. */
@Service
@DependsOn("startupCleanup")
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final String CONV_PREFIX = "uc9:conversation:";
    private static final long CONV_TTL_SECONDS = 600;
    private static final int MAX_MESSAGES = 20;

    private final StringRedisTemplate redis;
    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final SemanticCacheService semanticCacheService;

    public AssistantService(StringRedisTemplate redis, OpenAiService openAiService, ObjectMapper objectMapper,
                            MemoryService memoryService, KnowledgeBaseService knowledgeBaseService,
                            SemanticCacheService semanticCacheService) {
        this.redis = redis;
        this.openAiService = openAiService;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.semanticCacheService = semanticCacheService;
    }

    /** Re-run init on all UC9 sub-services (used by AdminController cleanup/reset flow). */
    public void init() {
        memoryService.init();
        knowledgeBaseService.init();
        semanticCacheService.init();
    }

    private int estimateTokens(String text) {
        return (text == null || text.isEmpty()) ? 0 : Math.max(1, text.length() / 4);
    }

    public Map<String, Object> chat(String sessionId, String userName, String userMessage) {
        long startTime = System.currentTimeMillis();
        String convKey = CONV_PREFIX + sessionId;
        List<Map<String, String>> messageHistory = loadConversation(convKey, userName);
        appendMessage(messageHistory, "user", userMessage);

        List<Map<String, String>> memoriesOut, kbOut, regDocsOut;
        String responseText;
        boolean semanticCacheHit = false;

        if (openAiService.isConfigured()) {
            Map<String, String> cached = semanticCacheService.checkSemanticCache(userMessage);
            memoriesOut = flatten(memoryService.vectorSearchMemories(userMessage, 3));
            kbOut = flatten(knowledgeBaseService.vectorSearchKB(userMessage, 3));
            regDocsOut = flatten(knowledgeBaseService.vectorSearchRegulationDocs(userMessage, 3));
            if (cached != null) {
                semanticCacheHit = true;
                semanticCacheService.recordHit();
                responseText = cached.get("response");
                int savedTokens = estimateTokens(userMessage) + estimateTokens(responseText);
                semanticCacheService.addTokensSaved(savedTokens);
                log.info("UC9: Semantic cache HIT for question: {} (~{} tokens saved)", userMessage, savedTokens);
            } else {
                semanticCacheService.recordMiss();
                List<Map<String, String>> combined = new ArrayList<>(kbOut);
                combined.addAll(regDocsOut);
                responseText = generateResponse(userMessage, memoriesOut, combined);
                int usedTokens = estimateTokens(userMessage) + estimateTokens(responseText);
                semanticCacheService.addTokensUsed(usedTokens);
                semanticCacheService.storeInSemanticCache(userMessage, responseText);
                log.info("UC9: Semantic cache MISS — stored response for: {} (~{} tokens used)", userMessage, usedTokens);
            }
        } else {
            memoriesOut = memoryService.findRelevantMemories(userMessage);
            kbOut = knowledgeBaseService.findRelevantKBDocs(userMessage);
            regDocsOut = new ArrayList<>();
            responseText = generateResponse(userMessage, memoriesOut, kbOut);
        }

        appendMessage(messageHistory, "assistant", responseText);
        saveConversation(convKey, userName, messageHistory);

        long latencyMs = System.currentTimeMillis() - startTime;
        int tokens = estimateTokens(userMessage) + estimateTokens(responseText);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId); result.put("response", responseText);
        result.put("memoriesRetrieved", memoriesOut); result.put("kbDocsRetrieved", kbOut);
        result.put("regDocsRetrieved", regDocsOut); result.put("conversationLength", messageHistory.size());
        result.put("latencyMs", latencyMs); result.put("semanticCacheHit", semanticCacheHit);
        result.put("semanticCacheEnabled", openAiService.isConfigured()); result.put("cacheLatencyMs", latencyMs);
        result.put(semanticCacheHit ? "tokensSaved" : "tokensUsed", tokens);
        return result;
    }

    public void chatStream(String sessionId, String userName, String userMessage, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        String convKey = CONV_PREFIX + sessionId;
        List<Map<String, String>> messageHistory = loadConversation(convKey, userName);
        appendMessage(messageHistory, "user", userMessage);

        Map<String, String> cached = semanticCacheService.checkSemanticCache(userMessage);
        boolean semanticCacheHit = cached != null;
        List<Map<String, Object>> memories = memoryService.vectorSearchMemories(userMessage, 3);
        List<Map<String, Object>> kb = knowledgeBaseService.vectorSearchKB(userMessage, 3);
        List<Map<String, Object>> regDocs = knowledgeBaseService.vectorSearchRegulationDocs(userMessage, 3);
        sendSources(emitter, memories, kb, regDocs, semanticCacheHit);

        String responseText;
        if (semanticCacheHit) {
            semanticCacheService.recordHit();
            responseText = cached.get("response");
            int savedTokens = estimateTokens(userMessage) + estimateTokens(responseText);
            semanticCacheService.addTokensSaved(savedTokens);
            log.info("UC9 Stream: Semantic cache HIT for: {} (~{} tokens saved)", userMessage, savedTokens);
            try {
                emitter.send(SseEmitter.event().name("token").data(
                        objectMapper.writeValueAsString(Map.of("content", responseText))));
            } catch (Exception e) { log.error("Failed to send cached response", e); }
        } else {
            semanticCacheService.recordMiss();
            List<Map<String, Object>> combined = new ArrayList<>(kb);
            combined.addAll(regDocs);
            List<Map<String, String>> openAiMessages = new ArrayList<>();
            openAiMessages.add(Map.of("role", "system", "content", buildSystemPrompt(memories, combined)));
            for (var msg : messageHistory) {
                openAiMessages.add(Map.of("role", msg.get("role"), "content", msg.get("content")));
            }
            responseText = openAiService.streamChatCompletion(openAiMessages, emitter);
            int usedTokens = estimateTokens(userMessage) + estimateTokens(responseText);
            semanticCacheService.addTokensUsed(usedTokens);
            semanticCacheService.storeInSemanticCache(userMessage, responseText);
            log.info("UC9 Stream: Semantic cache MISS — stored response for: {} (~{} tokens used)", userMessage, usedTokens);
        }

        appendMessage(messageHistory, "assistant", responseText);
        saveConversation(convKey, userName, messageHistory);
        sendDone(emitter, sessionId, messageHistory.size(),
                System.currentTimeMillis() - startTime, semanticCacheHit,
                estimateTokens(userMessage) + estimateTokens(responseText));
    }

    private void sendSources(SseEmitter emitter, List<Map<String, Object>> memories,
                             List<Map<String, Object>> kb, List<Map<String, Object>> regDocs, boolean cacheHit) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("memories", memories); data.put("kbDocs", kb);
            data.put("regDocs", regDocs); data.put("semanticCacheHit", cacheHit);
            emitter.send(SseEmitter.event().name("sources").data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) { log.error("Failed to send sources event", e); }
    }

    private void sendDone(SseEmitter emitter, String sessionId, int convLen, long latencyMs, boolean cacheHit, int tokens) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversationLength", convLen); data.put("latencyMs", latencyMs); data.put("sessionId", sessionId);
            data.put("semanticCacheHit", cacheHit); data.put("semanticCacheEnabled", true);
            data.put(cacheHit ? "tokensSaved" : "tokensUsed", tokens);
            emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(data)));
            emitter.complete();
        } catch (Exception e) { log.error("Failed to send done event", e); }
    }

    private List<Map<String, String>> loadConversation(String convKey, String userName) {
        Map<Object, Object> convData = redis.opsForHash().entries(convKey);
        if (convData.isEmpty()) {
            redis.opsForHash().put(convKey, "created_at", Instant.now().toString());
            redis.opsForHash().put(convKey, "user_name", userName);
            return new ArrayList<>();
        }
        return parseMessages(convData.getOrDefault("messages", "[]").toString());
    }

    private void appendMessage(List<Map<String, String>> history, String role, String content) {
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", role); msg.put("content", content); msg.put("timestamp", Instant.now().toString());
        history.add(msg);
        if (history.size() > MAX_MESSAGES) {
            var trimmed = new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()));
            history.clear(); history.addAll(trimmed);
        }
    }

    private void saveConversation(String convKey, String userName, List<Map<String, String>> history) {
        try {
            redis.opsForHash().put(convKey, "messages", objectMapper.writeValueAsString(history));
            redis.opsForHash().put(convKey, "last_active", Instant.now().toString());
            redis.opsForHash().put(convKey, "user_name", userName);
            redis.expire(convKey, CONV_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) { throw new RuntimeException("Failed to serialize messages", e); }
    }

    public Map<String, Object> getConversation(String sessionId) {
        String convKey = CONV_PREFIX + sessionId;
        Map<Object, Object> data = redis.opsForHash().entries(convKey);
        if (data.isEmpty()) return Map.of("exists", false);

        Long ttl = redis.getExpire(convKey, TimeUnit.SECONDS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true); result.put("sessionId", sessionId); result.put("redisKey", convKey);
        result.put("userName", data.getOrDefault("user_name", ""));
        result.put("createdAt", data.getOrDefault("created_at", ""));
        result.put("lastActive", data.getOrDefault("last_active", ""));
        result.put("ttl", ttl != null ? ttl : -1);
        result.put("messages", parseMessages(data.getOrDefault("messages", "[]").toString()));
        return result;
    }

    private List<Map<String, String>> parseMessages(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return new ArrayList<>(); }
    }

    public List<Map<String, String>> listMemories() { return memoryService.listMemories(); }
    public List<Map<String, String>> listKBArticles() { return knowledgeBaseService.listKBArticles(); }
    public Map<String, String> getKBArticle(String id) { return knowledgeBaseService.getKBArticle(id); }
    public Map<String, Object> getSemanticCacheStats() { return semanticCacheService.getSemanticCacheStats(); }

    private String buildSystemPrompt(List<Map<String, Object>> memories, List<Map<String, Object>> kbDocs) {
        StringBuilder sb = new StringBuilder("You are a helpful AI banking assistant. Answer questions based on the provided context.\n\n");
        if (!kbDocs.isEmpty()) {
            sb.append("## Relevant Knowledge Base Articles:\n");
            for (var doc : kbDocs) sb.append("- [").append(doc.get("id")).append("] ").append(doc.get("title"))
                    .append(": ").append(doc.get("content")).append("\n");
            sb.append("\n");
        }
        if (!memories.isEmpty()) {
            sb.append("## Relevant Past Interactions:\n");
            for (var mem : memories) sb.append("- [").append(mem.get("id")).append("] ").append(mem.get("summary"))
                    .append(": ").append(mem.get("detail")).append("\n");
            sb.append("\n");
        }
        sb.append("Use the above context to provide accurate, helpful responses. ")
          .append("If referencing specific documents or memories, mention their IDs. Be concise and professional.");
        return sb.toString();
    }

    private String generateResponse(String query, List<Map<String, String>> memories, List<Map<String, String>> kbDocs) {
        StringBuilder sb = new StringBuilder();
        if (!kbDocs.isEmpty()) {
            sb.append("Based on our banking knowledge base, here's what I found:\n\n");
            for (var doc : kbDocs) sb.append("**").append(doc.get("title")).append("**: ").append(doc.get("content")).append("\n\n");
        }
        if (!memories.isEmpty()) {
            sb.append("I also found relevant context from your previous interactions:\n\n");
            for (var mem : memories) sb.append("*").append(mem.get("summary")).append("* (").append(mem.get("date"))
                    .append("): ").append(mem.get("detail")).append("\n\n");
        }
        if (sb.length() == 0) sb.append(getDefaultResponse(query));
        return sb.toString().trim();
    }

    private String getDefaultResponse(String query) {
        String lower = query.toLowerCase();
        if (lower.contains("hello") || lower.contains("hi") || lower.contains("hey"))
            return "Hello! I'm your AI Banking Assistant. I can help you with account information, transfers, loans, investments, and more. What would you like to know?";
        if (lower.contains("help"))
            return "I can assist you with:\n• Account types and features\n• Transfer limits and fees (SEPA, SWIFT)\n• Card security and fraud prevention\n• Investment products\n• Loan and mortgage rates\n• Insurance products\n• Open Banking and PSD2\n\nWhat topic interests you?";
        if (lower.contains("thank")) return "You're welcome! Is there anything else I can help you with today?";
        return "I understand you're asking about \"" + query + "\". Let me look into that for you. Could you provide more details? You can ask about transfers, accounts, investments, loans, cards, insurance, or regulations.";
    }

    private static List<Map<String, String>> flatten(List<Map<String, Object>> rows) {
        List<Map<String, String>> out = new ArrayList<>();
        for (var row : rows) {
            Map<String, String> flat = new LinkedHashMap<>();
            row.forEach((k, v) -> flat.put(k, v != null ? v.toString() : ""));
            out.add(flat);
        } return out;
    }

    public void reset() {
        Set<String> convKeys = RedisScanHelper.scanKeys(redis, CONV_PREFIX + "*");
        if (!convKeys.isEmpty()) redis.delete(convKeys);
        memoryService.reset();
        knowledgeBaseService.reset();
        semanticCacheService.reset();
    }
}
