package com.redis.workshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.config.RedisScanHelper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class AgentCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinatorService.class);

    private static final String KEY_PREFIX = "uc17:";
    private static final String TASK_STREAM = KEY_PREFIX + "stream:tasks";
    private static final String RESULT_STREAM = KEY_PREFIX + "stream:results";
    private static final String EVENT_STREAM = KEY_PREFIX + "stream:events";
    private static final String TASK_GROUP = KEY_PREFIX + "group:tasks";
    private static final String RESULT_GROUP = KEY_PREFIX + "group:results";
    private static final String AGENT_HASH_PREFIX = KEY_PREFIX + "agent:";
    private static final long MAX_STREAM_LEN = 500L;
    private static final List<String> REDIS_COMMANDS_USED = List.of(
            "XGROUP CREATE", "XADD", "XREADGROUP", "XACK", "HSET", "HGETALL", "XREVRANGE");

    private static final List<AgentDefinition> AGENTS = List.of(
            new AgentDefinition("risk-analyst", "Risk Analyst", "Portfolio risk and stress exposure",
                    "Quantify concentration, market and drawdown exposure for the user request.", 260L),
            new AgentDefinition("compliance-advisor", "Compliance Advisor", "Regulatory obligations and controls",
                    "Highlight MiFID, PSD2, GDPR or policy obligations relevant to the query.", 320L),
            new AgentDefinition("portfolio-advisor", "Portfolio Advisor", "Allocation and next-best-action guidance",
                    "Recommend allocation or rebalancing actions that fit the banking scenario.", 300L),
            new AgentDefinition("fraud-analyst", "Fraud Analyst", "Fraud and anomaly posture",
                    "Flag suspicious patterns, abuse vectors or monitoring actions related to the request.", 280L)
    );

    private final StringRedisTemplate redis;
    private final KnowledgeBaseService knowledgeBaseService;
    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final ExecutorService agentExecutor = Executors.newFixedThreadPool(AGENTS.size());
    private final Semaphore coordinationGate = new Semaphore(1);

    public AgentCoordinatorService(StringRedisTemplate redis,
                                   KnowledgeBaseService knowledgeBaseService,
                                   OpenAiService openAiService,
                                   ObjectMapper objectMapper) {
        this.redis = redis;
        this.knowledgeBaseService = knowledgeBaseService;
        this.openAiService = openAiService;
        this.objectMapper = objectMapper;
    }

    public void init() {
        createConsumerGroup(TASK_STREAM, TASK_GROUP);
        createConsumerGroup(RESULT_STREAM, RESULT_GROUP);
        ensureStream(EVENT_STREAM);
        seedAgentHashes();
    }

    public void coordinate(String query, String userId, SseEmitter emitter) {
        String trimmedQuery = query == null ? "" : query.trim();
        String effectiveUserId = (userId == null || userId.isBlank()) ? "demo-user" : userId.trim();
        Object emitterLock = new Object();

        if (trimmedQuery.isBlank()) {
            emitError(emitter, emitterLock, null, null, "query is required");
            safeComplete(emitter);
            return;
        }

        boolean acquired = coordinationGate.tryAcquire();
        if (!acquired) {
            emitError(emitter, emitterLock, null, null,
                    "Coordinator is already processing another request. Please wait and retry.");
            safeComplete(emitter);
            return;
        }

        long startedAtNs = System.nanoTime();
        String requestId = UUID.randomUUID().toString();

        try {
            List<Map<String, Object>> plannedAgents = new ArrayList<>();
            for (AgentDefinition definition : AGENTS) {
                plannedAgents.add(planEntry(definition));
                updateAgentState(definition, requestId, "queued", definition.task(), 0L, 0, "");
            }

            emitEvent(emitter, emitterLock, requestId, "plan", Map.of(
                    "agents", plannedAgents,
                    "query", trimmedQuery,
                    "userId", effectiveUserId
            ));

            for (AgentDefinition definition : AGENTS) {
                enqueueTask(requestId, effectiveUserId, trimmedQuery, definition);
            }

            List<CompletableFuture<AgentResult>> futures = new ArrayList<>();
            for (int i = 0; i < AGENTS.size(); i++) {
                String consumerName = "uc17-worker-" + (i + 1);
                futures.add(CompletableFuture.supplyAsync(
                        () -> processNextTask(consumerName, emitter, emitterLock),
                        agentExecutor));
            }

            List<AgentResult> localResults = futures.stream().map(CompletableFuture::join).toList();
            List<AgentResult> collectedResults = collectResultsFromStream(requestId, AGENTS.size());
            if (collectedResults.size() != AGENTS.size()) {
                collectedResults = localResults;
            }

            List<Map<String, Object>> agentSummaries = new ArrayList<>();
            for (AgentResult result : collectedResults) {
                agentSummaries.add(toAgentSummary(result));
            }

            emitEvent(emitter, emitterLock, requestId, "assembling", Map.of(
                    "status", "assembling",
                    "agentResults", agentSummaries
            ));

            Map<String, Object> finalPayload = new LinkedHashMap<>();
            finalPayload.put("response", assembleResponse(trimmedQuery, collectedResults));
            finalPayload.put("totalLatencyMs", elapsedMs(startedAtNs));
            finalPayload.put("agentSummaries", agentSummaries);
            finalPayload.put("redisCommandsUsed", REDIS_COMMANDS_USED);
            finalPayload.put("mode", openAiService.isConfigured() ? "openai" : "mock");

            emitEvent(emitter, emitterLock, requestId, "result", finalPayload);
            safeComplete(emitter);
        } catch (Exception e) {
            log.error("UC17 coordination failed", e);
            emitError(emitter, emitterLock, requestId, null, e.getMessage() == null ? "UC17 coordination failed" : e.getMessage());
            safeComplete(emitter);
        } finally {
            coordinationGate.release();
        }
    }

    public Map<String, Object> getAgentStatus() {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentDefinition definition : AGENTS) {
            Map<Object, Object> raw = redis.opsForHash().entries(agentKey(definition.id()));
            Map<String, Object> entry = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> value : raw.entrySet()) {
                entry.put(String.valueOf(value.getKey()), value.getValue());
            }
            if (entry.isEmpty()) {
                entry.putAll(planEntry(definition));
                entry.put("status", "idle");
            }
            agents.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", openAiService.isConfigured() ? "openai" : "mock");
        result.put("busy", coordinationGate.availablePermits() == 0);
        result.put("agents", agents);
        return result;
    }

    public Map<String, Object> getRecentEvents(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().reverseRange(
                EVENT_STREAM, Range.unbounded(), Limit.limit().count(boundedLimit));
        if (records == null) {
            records = Collections.emptyList();
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", record.getId().getValue());
            for (Map.Entry<Object, Object> value : record.getValue().entrySet()) {
                entry.put(String.valueOf(value.getKey()), value.getValue());
            }
            entries.add(entry);
        }

        return Map.of("count", entries.size(), "entries", entries);
    }

    public Map<String, Object> reset() {
        Set<String> keys = RedisScanHelper.scanKeys(redis, KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        init();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("message", "UC17 agent coordinator state reset complete");
        result.put("agentCount", AGENTS.size());
        return result;
    }

    @PreDestroy
    public void cleanup() {
        agentExecutor.shutdownNow();
    }

    private AgentResult processNextTask(String consumerName, SseEmitter emitter, Object emitterLock) {
        AgentTask task = readNextTask(consumerName);
        AgentDefinition definition = task.definition();
        long startedAtNs = System.nanoTime();

        try {
            updateAgentState(definition, task.requestId(), "working", definition.task(), 0L, 0, "");
            emitEvent(emitter, emitterLock, task.requestId(), "agent-start", Map.of(
                    "agent", definition.name(),
                    "agentId", definition.id(),
                    "task", definition.task(),
                    "status", "working"
            ));

            sleepQuietly(definition.simulatedDelayMs() / 2);
            List<Map<String, Object>> ragResults = gatherRagResults(task.query());
            List<Map<String, Object>> tools = simulateTools(definition, task.query(), ragResults);

            updateAgentState(definition, task.requestId(), "tooling", definition.task(), 0L, 0, "");
            Map<String, Object> toolPayload = new LinkedHashMap<>();
            toolPayload.put("agent", definition.name());
            toolPayload.put("agentId", definition.id());
            toolPayload.put("tools", tools);
            toolPayload.put("ragResults", ragResults);
            emitEvent(emitter, emitterLock, task.requestId(), "agent-tools", toolPayload);

            sleepQuietly(definition.simulatedDelayMs() / 2);
            String response = runAgent(definition, task.query(), tools, ragResults);
            int tokensUsed = estimateTokens(response);
            long latencyMs = elapsedMs(startedAtNs);

            AgentResult result = new AgentResult(
                    task.requestId(), definition.id(), definition.name(), definition.role(), definition.task(),
                    response, latencyMs, tokensUsed, tools, ragResults, "done"
            );

            appendResult(result);
            updateAgentState(definition, task.requestId(), "done", definition.task(), latencyMs, tokensUsed, response);
            emitEvent(emitter, emitterLock, task.requestId(), "agent-done", Map.of(
                    "agent", definition.name(),
                    "agentId", definition.id(),
                    "response", response,
                    "latencyMs", latencyMs,
                    "tokensUsed", tokensUsed,
                    "status", "done"
            ));
            return result;
        } catch (Exception e) {
            log.warn("UC17 agent {} failed, using fallback: {}", definition.name(), e.getMessage());
            emitError(emitter, emitterLock, task.requestId(), definition.name(),
                    e.getMessage() == null ? "Agent execution failed" : e.getMessage());

            String response = buildMockResponse(definition, task.query(), List.of(), List.of());
            int tokensUsed = estimateTokens(response);
            long latencyMs = elapsedMs(startedAtNs);
            AgentResult fallback = new AgentResult(
                    task.requestId(), definition.id(), definition.name(), definition.role(), definition.task(),
                    response, latencyMs, tokensUsed, List.of(), List.of(), "done"
            );

            appendResult(fallback);
            updateAgentState(definition, task.requestId(), "done", definition.task(), latencyMs, tokensUsed, response);
            emitEvent(emitter, emitterLock, task.requestId(), "agent-done", Map.of(
                    "agent", definition.name(),
                    "agentId", definition.id(),
                    "response", response,
                    "latencyMs", latencyMs,
                    "tokensUsed", tokensUsed,
                    "status", "done"
            ));
            return fallback;
        } finally {
            acknowledge(TASK_STREAM, TASK_GROUP, task.recordId());
        }
    }

    private AgentTask readNextTask(String consumerName) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(TASK_GROUP, consumerName),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(TASK_STREAM, ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) {
            throw new IllegalStateException("No task available for consumer " + consumerName);
        }

        MapRecord<String, Object, Object> record = records.get(0);
        Map<String, String> fields = toStringMap(record.getValue());
        String agentId = fields.getOrDefault("agentId", "");
        AgentDefinition definition = definitionById(agentId);
        return new AgentTask(
                fields.getOrDefault("requestId", ""),
                fields.getOrDefault("userId", "demo-user"),
                fields.getOrDefault("query", ""),
                definition,
                record.getId()
        );
    }

    private void enqueueTask(String requestId, String userId, String query, AgentDefinition definition) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("requestId", requestId);
        fields.put("userId", userId);
        fields.put("query", query);
        fields.put("agentId", definition.id());
        fields.put("agent", definition.name());
        fields.put("role", definition.role());
        fields.put("task", definition.task());
        fields.put("status", "queued");
        fields.put("createdAt", Instant.now().toString());
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(TASK_STREAM));
        redis.opsForStream().trim(TASK_STREAM, MAX_STREAM_LEN);
    }

    private void appendResult(AgentResult result) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("requestId", result.requestId());
        fields.put("agentId", result.agentId());
        fields.put("agent", result.agent());
        fields.put("role", result.role());
        fields.put("task", result.task());
        fields.put("response", result.response());
        fields.put("latencyMs", String.valueOf(result.latencyMs()));
        fields.put("tokensUsed", String.valueOf(result.tokensUsed()));
        fields.put("status", result.status());
        fields.put("createdAt", Instant.now().toString());
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(RESULT_STREAM));
        redis.opsForStream().trim(RESULT_STREAM, MAX_STREAM_LEN);
    }

    private List<AgentResult> collectResultsFromStream(String requestId, int expectedCount) {
        List<AgentResult> results = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 3000L;

        while (results.size() < expectedCount && System.currentTimeMillis() < deadline) {
            int remaining = expectedCount - results.size();
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(RESULT_GROUP, "uc17-assembler"),
                    StreamReadOptions.empty().count(remaining).block(Duration.ofMillis(250)),
                    StreamOffset.create(RESULT_STREAM, ReadOffset.lastConsumed()));

            if (records == null || records.isEmpty()) {
                continue;
            }

            for (MapRecord<String, Object, Object> record : records) {
                Map<String, String> fields = toStringMap(record.getValue());
                acknowledge(RESULT_STREAM, RESULT_GROUP, record.getId());
                if (!requestId.equals(fields.get("requestId"))) {
                    continue;
                }
                results.add(new AgentResult(
                        fields.getOrDefault("requestId", ""),
                        fields.getOrDefault("agentId", ""),
                        fields.getOrDefault("agent", ""),
                        fields.getOrDefault("role", ""),
                        fields.getOrDefault("task", ""),
                        fields.getOrDefault("response", ""),
                        parseLong(fields.get("latencyMs")),
                        (int) parseLong(fields.get("tokensUsed")),
                        List.of(),
                        List.of(),
                        fields.getOrDefault("status", "done")
                ));
            }
        }

        return results;
    }

    private List<Map<String, Object>> gatherRagResults(String query) {
        List<Map<String, Object>> combined = new ArrayList<>();
        combined.addAll(knowledgeBaseService.vectorSearchKB(query, 2));
        combined.addAll(knowledgeBaseService.vectorSearchRegulationDocs(query, 2));

        List<Map<String, Object>> simplified = new ArrayList<>();
        for (Map<String, Object> entry : combined) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", String.valueOf(entry.getOrDefault("title", entry.getOrDefault("source", "Context item"))));
            item.put("score", entry.getOrDefault("score", "0"));
            if (entry.containsKey("source")) {
                item.put("source", entry.get("source"));
            }
            if (entry.containsKey("docType")) {
                item.put("docType", entry.get("docType"));
            }
            simplified.add(item);
            if (simplified.size() == 4) {
                break;
            }
        }
        return simplified;
    }

    private List<Map<String, Object>> simulateTools(AgentDefinition definition,
                                                    String query,
                                                    List<Map<String, Object>> ragResults) {
        int fingerprint = Math.abs((definition.id() + query).hashCode());
        String contextTitle = ragResults.isEmpty()
                ? "No matching regulation chunk"
                : String.valueOf(ragResults.get(0).getOrDefault("title", "Context item"));

        if ("risk-analyst".equals(definition.id())) {
            return List.of(
                    tool("stress-scan", "Interest-rate shock suggests a " + band(fingerprint % 100) + " portfolio impact band."),
                    tool("concentration-check", "Largest concentration cluster appears in " + contextTitle + ".")
            );
        }
        if ("compliance-advisor".equals(definition.id())) {
            return List.of(
                    tool("policy-lookup", "Matched policy context: " + contextTitle + "."),
                    tool("obligation-check", "Disclosure and auditability controls should be retained for this workflow.")
            );
        }
        if ("portfolio-advisor".equals(definition.id())) {
            return List.of(
                    tool("allocation-simulator", "Scenario engine recommends a " + tilt(fingerprint % 3) + " tilt for the requested scenario."),
                    tool("rebalance-window", "Suggested rebalancing window: next 1-2 review cycles.")
            );
        }
        return List.of(
                tool("anomaly-scan", "Anomaly score sits in the " + band((fingerprint / 3) % 100) + " bucket for the pattern described."),
                tool("control-check", "Recommend step-up monitoring and user verification for unusual sequence changes.")
        );
    }

    private String runAgent(AgentDefinition definition,
                            String query,
                            List<Map<String, Object>> tools,
                            List<Map<String, Object>> ragResults) {
        return switch (definition.id()) {
            case "risk-analyst" -> riskAnalyst(query, tools, ragResults);
            case "compliance-advisor" -> complianceAdvisor(query, tools, ragResults);
            case "portfolio-advisor" -> portfolioAdvisor(query, tools, ragResults);
            case "fraud-analyst" -> fraudAnalyst(query, tools, ragResults);
            default -> buildMockResponse(definition, query, tools, ragResults);
        };
    }

    private String riskAnalyst(String query, List<Map<String, Object>> tools, List<Map<String, Object>> ragResults) {
        return callOpenAiOrMock(
                definitionById("risk-analyst"),
                query,
                tools,
                ragResults,
                "You are the Risk Analyst for a banking workshop demo. Respond in 3 concise bullets plus one recommendation. Focus on market risk, concentration and downside exposure.");
    }

    private String complianceAdvisor(String query, List<Map<String, Object>> tools, List<Map<String, Object>> ragResults) {
        return callOpenAiOrMock(
                definitionById("compliance-advisor"),
                query,
                tools,
                ragResults,
                "You are the Compliance Advisor for a banking workshop demo. Respond in 3 concise bullets plus one recommendation. Focus on controls, explainability, customer disclosure and regulation touchpoints.");
    }

    private String portfolioAdvisor(String query, List<Map<String, Object>> tools, List<Map<String, Object>> ragResults) {
        return callOpenAiOrMock(
                definitionById("portfolio-advisor"),
                query,
                tools,
                ragResults,
                "You are the Portfolio Advisor for a banking workshop demo. Respond in 3 concise bullets plus one recommendation. Focus on asset mix, rebalancing and client-facing actionability.");
    }

    private String fraudAnalyst(String query, List<Map<String, Object>> tools, List<Map<String, Object>> ragResults) {
        return callOpenAiOrMock(
                definitionById("fraud-analyst"),
                query,
                tools,
                ragResults,
                "You are the Fraud Analyst for a banking workshop demo. Respond in 3 concise bullets plus one recommendation. Focus on anomaly patterns, abuse risk and monitoring controls.");
    }

    private String callOpenAiOrMock(AgentDefinition definition,
                                    String query,
                                    List<Map<String, Object>> tools,
                                    List<Map<String, Object>> ragResults,
                                    String systemPrompt) {
        String fallback = buildMockResponse(definition, query, tools, ragResults);
        if (!openAiService.isConfigured()) {
            return fallback;
        }

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content",
                        "User query: " + query + "\n\n" +
                                "Tool results:\n" + renderTools(tools) + "\n\n" +
                                "Retrieved context:\n" + renderRag(ragResults))
        );

        try {
            return openAiService.chatCompletion(messages);
        } catch (Exception e) {
            log.warn("UC17 OpenAI call failed for {}: {}", definition.name(), e.getMessage());
            return fallback;
        }
    }

    private String buildMockResponse(AgentDefinition definition,
                                     String query,
                                     List<Map<String, Object>> tools,
                                     List<Map<String, Object>> ragResults) {
        String firstTool = tools.isEmpty() ? "No tool output" : String.valueOf(tools.get(0).get("result"));
        String firstDoc = ragResults.isEmpty() ? "general banking guidance" : String.valueOf(ragResults.get(0).get("title"));

        if ("risk-analyst".equals(definition.id())) {
            return "• Risk posture: the request points to moderate downside sensitivity rather than acute distress.\n"
                    + "• Tool signal: " + firstTool + "\n"
                    + "• Retrieved context: " + firstDoc + " reinforces monitoring of concentration and rate sensitivity.\n"
                    + "Recommendation: run a focused stress test before increasing exposure tied to ‘" + query + "’.";
        }
        if ("compliance-advisor".equals(definition.id())) {
            return "• Compliance posture: this workflow needs explainability, retained evidence and customer disclosure.\n"
                    + "• Tool signal: " + firstTool + "\n"
                    + "• Retrieved context: " + firstDoc + " suggests documenting suitability and control checkpoints.\n"
                    + "Recommendation: keep a decision trace and review the customer-facing rationale before execution.";
        }
        if ("portfolio-advisor".equals(definition.id())) {
            return "• Portfolio view: the request is better served with incremental adjustment than a wholesale rebalance.\n"
                    + "• Tool signal: " + firstTool + "\n"
                    + "• Retrieved context: " + firstDoc + " supports phased allocation changes and review windows.\n"
                    + "Recommendation: stage the change over the next review cycle and keep liquidity available.";
        }
        return "• Fraud posture: the request does not indicate confirmed abuse, but it merits monitoring for unusual sequence changes.\n"
                + "• Tool signal: " + firstTool + "\n"
                + "• Retrieved context: " + firstDoc + " suggests extra verification when behavior changes quickly.\n"
                + "Recommendation: keep anomaly thresholds active and require step-up verification for exceptional actions.";
    }

    private String assembleResponse(String query, List<AgentResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("Coordinator summary for: ").append(query).append("\n\n");

        for (AgentDefinition definition : AGENTS) {
            AgentResult result = findResult(results, definition.id());
            if (result == null) {
                continue;
            }
            builder.append("• ").append(result.agent()).append(": ")
                    .append(firstSentence(result.response())).append("\n");
        }

        builder.append("\nRecommended next step: align the risk, compliance and fraud controls first, then action the portfolio change in a staged way.");
        return builder.toString();
    }

    private void emitEvent(SseEmitter emitter,
                           Object emitterLock,
                           String requestId,
                           String name,
                           Map<String, Object> data) {
        String json = toJson(data);
        appendEventRecord(requestId, name, data, json);

        synchronized (emitterLock) {
            try {
                emitter.send(SseEmitter.event().name(name).data(json));
            } catch (IOException e) {
                log.debug("UC17 SSE send failed for event {}: {}", name, e.getMessage());
            }
        }
    }

    private void emitError(SseEmitter emitter,
                           Object emitterLock,
                           String requestId,
                           String agent,
                           String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message == null ? "Unknown error" : message);
        if (agent != null) {
            payload.put("agent", agent);
        }
        emitEvent(emitter, emitterLock, requestId, "error", payload);
    }

    private void appendEventRecord(String requestId,
                                   String name,
                                   Map<String, Object> data,
                                   String jsonPayload) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("requestId", requestId == null ? "" : requestId);
        fields.put("event", name);
        fields.put("timestamp", Instant.now().toString());
        fields.put("payload", jsonPayload);
        Object agent = data.get("agent");
        if (agent != null) {
            fields.put("agent", String.valueOf(agent));
        }
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(EVENT_STREAM));
        redis.opsForStream().trim(EVENT_STREAM, MAX_STREAM_LEN);
    }

    private void createConsumerGroup(String streamKey, String groupName) {
        try {
            redis.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    bytes("CREATE"),
                    bytes(streamKey),
                    bytes(groupName),
                    bytes("$"),
                    bytes("MKSTREAM")
            ));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toUpperCase(Locale.ROOT);
            if (!message.contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void ensureStream(String streamKey) {
        RecordId bootstrapId = redis.opsForStream().add(StreamRecords.string(Map.of("bootstrap", "1")).withStreamKey(streamKey));
        if (bootstrapId != null) {
            redis.opsForStream().delete(streamKey, bootstrapId);
        }
    }

    private void seedAgentHashes() {
        for (AgentDefinition definition : AGENTS) {
            updateAgentState(definition, "", "idle", definition.task(), 0L, 0, "");
        }
    }

    private void updateAgentState(AgentDefinition definition,
                                  String requestId,
                                  String status,
                                  String task,
                                  long latencyMs,
                                  int tokensUsed,
                                  String response) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", definition.id());
        fields.put("name", definition.name());
        fields.put("role", definition.role());
        fields.put("task", task);
        fields.put("status", status);
        fields.put("requestId", requestId == null ? "" : requestId);
        fields.put("latencyMs", String.valueOf(latencyMs));
        fields.put("tokensUsed", String.valueOf(tokensUsed));
        fields.put("response", truncate(response, 280));
        fields.put("lastUpdated", Instant.now().toString());
        redis.opsForHash().putAll(agentKey(definition.id()), fields);
    }

    private Map<String, Object> planEntry(AgentDefinition definition) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", definition.id());
        entry.put("name", definition.name());
        entry.put("role", definition.role());
        entry.put("task", definition.task());
        return entry;
    }

    private Map<String, Object> toAgentSummary(AgentResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("agent", result.agent());
        summary.put("agentId", result.agentId());
        summary.put("role", result.role());
        summary.put("task", result.task());
        summary.put("response", result.response());
        summary.put("latencyMs", result.latencyMs());
        summary.put("tokensUsed", result.tokensUsed());
        summary.put("status", result.status());
        return summary;
    }

    private AgentDefinition definitionById(String agentId) {
        for (AgentDefinition definition : AGENTS) {
            if (definition.id().equals(agentId)) {
                return definition;
            }
        }
        throw new IllegalArgumentException("Unknown agent id: " + agentId);
    }

    private AgentResult findResult(List<AgentResult> results, String agentId) {
        for (AgentResult result : results) {
            if (result.agentId().equals(agentId)) {
                return result;
            }
        }
        return null;
    }

    private void acknowledge(String streamKey, String groupName, RecordId recordId) {
        try {
            redis.opsForStream().acknowledge(streamKey, groupName, recordId);
        } catch (Exception e) {
            log.debug("UC17 acknowledge skipped for {} {}: {}", streamKey, recordId, e.getMessage());
        }
    }

    private Map<String, String> toStringMap(Map<Object, Object> raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            fields.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return fields;
    }

    private Map<String, Object> tool(String name, String result) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("result", result);
        return tool;
    }

    private String renderTools(List<Map<String, Object>> tools) {
        if (tools.isEmpty()) {
            return "- No simulated tools";
        }
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> tool : tools) {
            builder.append("- ")
                    .append(tool.getOrDefault("name", "tool"))
                    .append(": ")
                    .append(tool.getOrDefault("result", ""))
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String renderRag(List<Map<String, Object>> ragResults) {
        if (ragResults.isEmpty()) {
            return "- No retrieved context available";
        }
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> entry : ragResults) {
            builder.append("- ")
                    .append(entry.getOrDefault("title", "Context item"))
                    .append(" (score=")
                    .append(entry.getOrDefault("score", "0"))
                    .append(")\n");
        }
        return builder.toString().trim();
    }

    private String band(int score) {
        if (score < 34) {
            return "low";
        }
        if (score < 67) {
            return "medium";
        }
        return "high";
    }

    private String tilt(int value) {
        return switch (value) {
            case 0 -> "defensive";
            case 1 -> "balanced";
            default -> "growth";
        };
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(24, (int) Math.ceil(text.length() / 4.0d));
    }

    private long elapsedMs(long startedAtNs) {
        return Math.max(0L, (System.nanoTime() - startedAtNs) / 1_000_000L);
    }

    private long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return "No response generated.";
        }
        String compact = text.replace('\n', ' ').trim();
        int sentenceEnd = compact.indexOf('.');
        return sentenceEnd > 0 ? compact.substring(0, sentenceEnd + 1) : compact;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize SSE payload", e);
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String agentKey(String agentId) {
        return AGENT_HASH_PREFIX + agentId;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record AgentDefinition(String id, String name, String role, String task, long simulatedDelayMs) {}

    private record AgentTask(String requestId, String userId, String query,
                             AgentDefinition definition, RecordId recordId) {}

    private record AgentResult(String requestId, String agentId, String agent, String role, String task,
                               String response, long latencyMs, int tokensUsed,
                               List<Map<String, Object>> tools, List<Map<String, Object>> ragResults,
                               String status) {}
}