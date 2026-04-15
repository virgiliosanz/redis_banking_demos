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
                    "icon", "🔑", "features", "Hash · TTL · HSET · HGET"),
            Map.of("id", "2", "title", "Session Storage",
                    "description", "Distributed session management with Redis as session store",
                    "icon", "📋", "features", "Hash · TTL · HGETALL"),
            Map.of("id", "3", "title", "User Profile Storage",
                    "description", "Aggregate user profiles from multiple databases",
                    "icon", "👤", "features", "Hash · HSET · HGETALL · HINCRBY"),
            Map.of("id", "4", "title", "Rate Limiting",
                    "description", "Protect APIs with Redis-based rate limiting",
                    "icon", "🚦", "features", "String · INCR · EXPIRE"),
            Map.of("id", "5", "title", "Transaction Deduplication",
                    "description", "Prevent duplicate transactions with Redis",
                    "icon", "🔄", "features", "Set · Bloom Filter · Hash · TTL"),
            Map.of("id", "6", "title", "Fraud Detection",
                    "description", "Real-time transaction risk scoring",
                    "icon", "🛡️", "features", "Sorted Set · Streams · RQE"),
            Map.of("id", "7", "title", "Feature Store",
                    "description", "Online feature store for ML models",
                    "icon", "📊", "features", "Hash · TTL · RQE"),
            Map.of("id", "8", "title", "Document Database",
                    "description", "Document storage with full-text and vector search",
                    "icon", "📄", "features", "Vector · RQE · JSON"),
            Map.of("id", "9", "title", "AI Agent Memory + RAG",
                    "description", "AI assistant with short/long-term memory and RAG",
                    "icon", "🤖", "features", "Hash · Vector · Streams · JSON · TTL"),
            Map.of("id", "10", "title", "Cache-Aside",
                    "description", "Speed up data access with Redis cache — from 200ms to <1ms",
                    "icon", "⚡", "features", "String · GET · SET EX · DEL"),
            Map.of("id", "11", "title", "Transaction Monitoring",
                    "description", "Live transaction metrics with Redis Streams",
                    "icon", "📈", "features", "Streams · XADD · XRANGE · XLEN"),
            Map.of("id", "12", "title", "ATM & Branch Finder",
                    "description", "Find nearest ATMs and branches with Redis Geospatial",
                    "icon", "📍", "features", "Geo · JSON · RQE · GEOSEARCH"),
            Map.of("id", "13", "title", "Distributed Locking",
                    "description", "Lock accounts during wire transfers with SET NX EX + Lua",
                    "icon", "🔒", "features", "SET NX EX · Lua · EVAL · TTL")
    );

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("useCases", USE_CASES);
        return "index";
    }
}
