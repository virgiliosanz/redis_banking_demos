package com.redis.workshop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller
public class HomeController {

    private static final List<Map<String, String>> USE_CASES = List.of(
            Map.of("id", "1", "title", "Authentication Token Store",
                    "description", "Store and validate auth tokens with Redis Hash + TTL",
                    "icon", "auth-token-storage", "features", "Hash · TTL · HSET · HGET"),
            Map.of("id", "2", "title", "Session Storage",
                    "description", "Distributed session management with Redis as session store",
                    "icon", "session-management", "features", "Hash · TTL · HGETALL"),
            Map.of("id", "3", "title", "User Profile Storage",
                    "description", "Aggregate user profiles from multiple databases",
                    "icon", "user-profile-storage", "features", "Hash · HSET · HGETALL · HINCRBY"),
            Map.of("id", "4", "title", "Rate Limiting",
                    "description", "Protect APIs with Redis-based rate limiting",
                    "icon", "metering", "features", "String · INCR · EXPIRE"),
            Map.of("id", "5", "title", "Transaction Deduplication",
                    "description", "Prevent duplicate transactions with Redis",
                    "icon", "deduplication", "features", "Set · Bloom Filter · Hash · TTL"),
            Map.of("id", "6", "title", "Fraud Detection",
                    "description", "Real-time transaction risk scoring",
                    "icon", "fraud-detection", "features", "Sorted Set · Streams · RQE"),
            Map.of("id", "7", "title", "Feature Store",
                    "description", "Online feature store for ML models",
                    "icon", "feature-store", "features", "Hash · TTL · RQE"),
            Map.of("id", "8", "title", "Document Database",
                    "description", "Document storage with full-text and vector search",
                    "icon", "document", "features", "Vector · RQE · JSON"),
            Map.of("id", "9", "title", "AI Agent Memory + RAG",
                    "description", "AI assistant with short/long-term memory and RAG",
                    "icon", "ai-agent-memory", "features", "Hash · Vector · Streams · JSON · TTL"),
            Map.of("id", "10", "title", "Cache-Aside",
                    "description", "Speed up data access with Redis cache — from 200ms to <1ms",
                    "icon", "caching", "features", "String · GET · SET EX · DEL"),
            Map.of("id", "11", "title", "Transaction Monitoring",
                    "description", "Live transaction metrics with Redis Streams",
                    "icon", "monitoring", "features", "Streams · XADD · XRANGE · XLEN"),
            Map.of("id", "12", "title", "ATM & Branch Finder",
                    "description", "Find nearest ATMs and branches with Redis Geospatial",
                    "icon", "geospatial-data", "features", "Geo · JSON · RQE · GEOSEARCH"),
            Map.of("id", "13", "title", "Distributed Locking",
                    "description", "Lock accounts during wire transfers with SET NX EX + Lua",
                    "icon", "security", "features", "SET NX EX · Lua · EVAL · TTL")
    );

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("useCases", USE_CASES);
        return "index";
    }
}
