package com.redis.workshop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller
public class HomeController {

    private static final List<Map<String, Object>> USE_CASE_GROUPS = List.of(
            useCaseGroup("Identity & Access", "🔐", List.of(
                    useCase("1", "Authentication Token Store",
                            "Store and validate auth tokens with Redis Hash + TTL",
                            "auth-token-storage", "Hash · TTL · HSET · HGET"),
                    useCase("2", "Session Storage",
                            "Distributed session management with Redis as session store",
                            "session-management", "Hash · TTL · HGETALL"),
                    useCase("3", "User Profile Storage",
                            "Aggregate user profiles from multiple databases",
                            "user-profile-storage", "Hash · HSET · HGETALL · HINCRBY")
            )),
            useCaseGroup("Transactions & Risk", "💳", List.of(
                    useCase("5", "Transaction Deduplication",
                            "Prevent duplicate transactions with Redis",
                            "deduplication", "Set · Bloom Filter · Hash · TTL"),
                    useCase("6", "Fraud Detection",
                            "Real-time transaction risk scoring",
                            "fraud-detection", "Sorted Set · Streams · RQE"),
                    useCase("11", "Transaction Monitoring",
                            "Live transaction metrics with Redis Streams",
                            "monitoring", "Streams · XADD · XRANGE · XLEN")
            )),
            useCaseGroup("Data & Caching", "📦", List.of(
                    useCase("7", "Feature Store",
                            "Online feature store for ML models",
                            "feature-store", "Hash · TTL · RQE"),
                    useCase("8", "Document Database",
                            "Document storage with full-text and vector search",
                            "document", "Vector · RQE · JSON"),
                    useCase("10", "Cache-Aside",
                            "Speed up data access with Redis cache — from 200ms to <1ms",
                            "caching", "String · GET · SET EX · DEL")
            )),
            useCaseGroup("Infrastructure", "⚙️", List.of(
                    useCase("4", "Rate Limiting",
                            "Protect APIs with Redis-based rate limiting",
                            "metering", "String · INCR · EXPIRE"),
                    useCase("12", "ATM & Branch Finder",
                            "Find nearest ATMs and branches with Redis Geospatial",
                            "geospatial-data", "Geo · JSON · RQE · GEOSEARCH"),
                    useCase("13", "Distributed Locking",
                            "Lock accounts during wire transfers with SET NX EX + Lua",
                            "security", "SET NX EX · Lua · EVAL · TTL")
            )),
            useCaseGroup("AI & Agents", "🤖", List.of(
                    useCase("9", "AI Agent Memory + RAG",
                            "AI assistant with short/long-term memory and RAG",
                            "ai-agent-memory", "Hash · Vector · Streams · JSON · TTL"),
                    useCase("14", "Agent Memory Server",
                            "Memory-only agent: working memory, long-term memory and context assembly via Redis AMS (synchronous chat, no RAG/docs)",
                            "ai-agent-memory", "AMS · REST · MCP · Context Assembly"),
                    useCase("15", "AI Guardrails",
                            "Banking chat with Redis-powered guardrails: rate limiting, topic routing, PII detection, prompt injection defense",
                            "security", "Vector · Streams · INCR · Hash"),
                    useCase("16", "AI Gateway",
                            "Route AI requests, apply semantic cache, rate limits, and observability from Redis",
                            "ai-agent-memory", "Vector · Hash · INCR · Streams · TTL"),
                    useCase("17", "AI Agent Coordination",
                            "Coordinate specialized AI agents with Redis Streams and Consumer Groups",
                            "ai-agent-memory", "Streams · Consumer Groups · Hash · RAG")
            ))
    );

    private static Map<String, Object> useCaseGroup(String name, String icon, List<Map<String, String>> useCases) {
        return Map.of("name", name, "icon", icon, "useCases", useCases);
    }

    private static Map<String, String> useCase(String id, String title, String description, String icon, String features) {
        return Map.of("id", id, "title", title, "description", description, "icon", icon, "features", features);
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("useCaseGroups", USE_CASE_GROUPS);
        return "index";
    }
}
