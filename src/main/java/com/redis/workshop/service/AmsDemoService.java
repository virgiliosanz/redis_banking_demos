package com.redis.workshop.service;

import com.redis.workshop.config.AmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator for the dedicated Agent Memory Server use case.
 *
 * Keeps the REST surface tiny and demo-friendly: seed + reset reuse a curated
 * banking subset from {@link AmsDemoData}; chat exercises AMS working memory
 * plus {@code /v1/memory/prompt} so the UI can render the full context-assembly
 * payload that was ultimately sent to the LLM. Everything is cleanly isolated
 * from UC9 ({@link AssistantService} and {@link MemoryService} are untouched).
 *
 * Failure handling is explicit: any {@link AmsException} surfaces up to the
 * controller / {@code GlobalExceptionHandler} with a 503 response so the
 * presenter sees a clear "AMS unavailable" message instead of a stack trace.
 */
@Service
public class AmsDemoService {

    private static final Logger log = LoggerFactory.getLogger(AmsDemoService.class);
    private static final int DEFAULT_SEARCH_LIMIT = 5;

    private final AmsClient ams;
    private final AmsProperties props;
    private final AmsTraceRecorder traces;
    private final OpenAiService openAiService;

    public AmsDemoService(AmsClient ams, AmsProperties props,
                          AmsTraceRecorder traces, OpenAiService openAiService) {
        this.ams = ams;
        this.props = props;
        this.traces = traces;
        this.openAiService = openAiService;
    }

    // -------- Status --------

    /** Best-effort reachability probe. Never throws; returns a flat payload for the UI. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseUrl", props.getBaseUrl());
        out.put("mcpUrl", props.getMcpUrl());
        out.put("namespace", props.getDefaultNamespace());
        out.put("openaiConfigured", openAiService.isConfigured());
        out.put("seededMemoryIds", AmsDemoData.seededIds());
        try {
            Map<String, Object> health = ams.health();
            out.put("reachable", true);
            out.put("health", health);
        } catch (AmsException e) {
            out.put("reachable", false);
            out.put("error", e.getMessage());
            out.put("errorStatus", e.getStatusCode());
        }
        return out;
    }

    // -------- Seed / reset --------

    /** Seed long-term memories and initialize an empty working memory for the session. */
    public Map<String, Object> seed(String sessionId, String userId) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> memories = new ArrayList<>();
        for (Map<String, Object> m : AmsDemoData.LONG_TERM_MEMORIES) {
            Map<String, Object> rec = new LinkedHashMap<>(m);
            rec.put("user_id", userId);
            rec.put("namespace", props.getDefaultNamespace());
            memories.add(rec);
        }
        Map<String, Object> createResp = ams.createLongTermMemories(memories, true);

        Map<String, Object> wm = new LinkedHashMap<>();
        wm.put("messages", List.of());
        wm.put("memories", List.of());
        wm.put("data", Map.of("demo_seeded_at", Instant.now().toString()));
        Map<String, Object> putResp = ams.putWorkingMemory(sessionId, wm, userId, props.getDefaultNamespace());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("sessionId", sessionId);
        out.put("userId", userId);
        out.put("namespace", props.getDefaultNamespace());
        out.put("seededMemoryIds", AmsDemoData.seededIds());
        out.put("longTermCreate", createResp);
        out.put("workingMemory", putResp);
        out.put("latencyMs", System.currentTimeMillis() - start);
        log.info("AMS demo seeded (session={}, user={}, memories={})",
                sessionId, userId, AmsDemoData.seededIds().size());
        return out;
    }

    /** Delete working memory for the session and remove the seeded long-term IDs. */
    public Map<String, Object> reset(String sessionId, String userId) {
        long start = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("userId", userId);
        out.put("namespace", props.getDefaultNamespace());

        try {
            out.put("workingMemoryDelete",
                    ams.deleteWorkingMemory(sessionId, userId, props.getDefaultNamespace()));
        } catch (AmsException e) {
            out.put("workingMemoryDelete", Map.of("status", "skipped", "error", e.getMessage()));
        }
        out.put("longTermDelete", ams.deleteLongTermMemoriesByIds(AmsDemoData.seededIds()));
        traces.clear();
        out.put("status", "ok");
        out.put("latencyMs", System.currentTimeMillis() - start);
        log.info("AMS demo reset (session={}, user={})", sessionId, userId);
        return out;
    }

    // -------- Chat turn --------

    /**
     * Drive one conversation turn through AMS. Flow:
     * <ol>
     *   <li>Load current working memory (so we can append to it).</li>
     *   <li>Append the user message and PUT the updated working memory.</li>
     *   <li>Call {@code /v1/memory/prompt} to get the context-assembled
     *       messages AMS would send to the LLM.</li>
     *   <li>Call OpenAI with those messages (or produce a deterministic
     *       mock if OpenAI is not configured).</li>
     *   <li>Append the assistant reply and PUT working memory again.</li>
     * </ol>
     */
    public Map<String, Object> chat(String sessionId, String userId, String userMessage) {
        long start = System.currentTimeMillis();
        String namespace = props.getDefaultNamespace();

        Map<String, Object> currentWm = safeGetWorkingMemory(sessionId, userId, namespace);
        List<Map<String, Object>> messages = extractMessages(currentWm);
        messages.add(msg("user", userMessage));

        Map<String, Object> afterUserWm = new LinkedHashMap<>(currentWm);
        afterUserWm.put("messages", messages);
        stripServerFields(afterUserWm);
        ams.putWorkingMemory(sessionId, afterUserWm, userId, namespace);

        Map<String, Object> promptReq = new LinkedHashMap<>();
        promptReq.put("query", userMessage);
        Map<String, Object> sessionCtx = new LinkedHashMap<>();
        sessionCtx.put("session_id", sessionId);
        sessionCtx.put("user_id", userId);
        sessionCtx.put("namespace", namespace);
        promptReq.put("session", sessionCtx);
        Map<String, Object> ltSearch = new LinkedHashMap<>();
        ltSearch.put("limit", DEFAULT_SEARCH_LIMIT);
        Map<String, String> userFilter = Map.of("eq", userId);
        ltSearch.put("user_id", userFilter);
        promptReq.put("long_term_search", ltSearch);
        Map<String, Object> promptResp = ams.memoryPrompt(promptReq);

        List<Map<String, String>> llmMessages = coerceToLlmMessages(promptResp);
        String responseText;
        if (openAiService.isConfigured()) {
            responseText = openAiService.chatCompletion(llmMessages);
        } else {
            responseText = mockResponse(userMessage, llmMessages);
        }

        messages.add(msg("assistant", responseText));
        Map<String, Object> afterAssistantWm = new LinkedHashMap<>(currentWm);
        afterAssistantWm.put("messages", messages);
        stripServerFields(afterAssistantWm);
        Map<String, Object> finalWm = ams.putWorkingMemory(sessionId, afterAssistantWm, userId, namespace);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("userId", userId);
        out.put("namespace", namespace);
        out.put("userMessage", userMessage);
        out.put("response", responseText);
        out.put("contextAssembly", promptResp);
        out.put("assembledMessages", llmMessages);
        out.put("workingMemory", finalWm);
        out.put("openaiUsed", openAiService.isConfigured());
        out.put("latencyMs", System.currentTimeMillis() - start);
        return out;
    }

    // -------- Passthroughs --------

    public Map<String, Object> getWorkingMemory(String sessionId, String userId) {
        return ams.getWorkingMemory(sessionId, userId, props.getDefaultNamespace());
    }

    public Map<String, Object> searchMemories(String query, String userId, Integer limit) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("text", query);
        req.put("limit", limit != null ? limit : DEFAULT_SEARCH_LIMIT);
        if (userId != null && !userId.isBlank()) {
            req.put("user_id", Map.of("eq", userId));
        }
        req.put("namespace", Map.of("eq", props.getDefaultNamespace()));
        return ams.searchLongTermMemories(req);
    }

    public Map<String, Object> memoryPrompt(String query, String sessionId, String userId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("query", query);
        if (sessionId != null && !sessionId.isBlank()) {
            Map<String, Object> sess = new LinkedHashMap<>();
            sess.put("session_id", sessionId);
            if (userId != null) sess.put("user_id", userId);
            sess.put("namespace", props.getDefaultNamespace());
            req.put("session", sess);
        }
        Map<String, Object> lt = new LinkedHashMap<>();
        lt.put("limit", DEFAULT_SEARCH_LIMIT);
        if (userId != null) lt.put("user_id", Map.of("eq", userId));
        req.put("long_term_search", lt);
        return ams.memoryPrompt(req);
    }

    public List<Map<String, Object>> recentTraces(int limit) {
        return traces.recent(limit);
    }

    // -------- Helpers --------

    private Map<String, Object> safeGetWorkingMemory(String sessionId, String userId, String namespace) {
        try {
            return new LinkedHashMap<>(ams.getWorkingMemory(sessionId, userId, namespace));
        } catch (AmsException e) {
            if (e.getStatusCode() == 404) {
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("messages", new ArrayList<Map<String, Object>>());
                fresh.put("memories", new ArrayList<>());
                fresh.put("session_id", sessionId);
                fresh.put("user_id", userId);
                fresh.put("namespace", namespace);
                return fresh;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractMessages(Map<String, Object> wm) {
        Object raw = wm.get("messages");
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object o : list) if (o instanceof Map<?, ?> m) out.add(new LinkedHashMap<>((Map<String, Object>) m));
            return out;
        }
        return new ArrayList<>();
    }

    /** Remove server-assigned fields that cannot be sent back on PUT. */
    private void stripServerFields(Map<String, Object> wm) {
        wm.remove("tokens");
        wm.remove("context_percentage_total_used");
        wm.remove("context_percentage_until_summarization");
        wm.remove("session_id");
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> coerceToLlmMessages(Map<String, Object> promptResp) {
        List<Map<String, String>> out = new ArrayList<>();
        Object raw = promptResp.get("messages");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object role = m.get("role");
                Object content = m.get("content");
                if (role == null || content == null) continue;
                out.add(Map.of("role", role.toString(), "content", content.toString()));
            }
        }
        return out;
    }

    private String mockResponse(String userMessage, List<Map<String, String>> assembled) {
        int systemCount = 0, userCount = 0, assistantCount = 0;
        for (var m : assembled) switch (m.get("role")) {
            case "system" -> systemCount++;
            case "user" -> userCount++;
            case "assistant" -> assistantCount++;
            default -> {}
        }
        return "[AMS demo — OpenAI not configured] Assembled context sent to the LLM: "
                + systemCount + " system / " + userCount + " user / " + assistantCount + " assistant message(s). "
                + "Your message was: \"" + userMessage + "\".";
    }
}
