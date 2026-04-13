package com.redis.workshop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller
public class HomeController {

    private static final List<Map<String, String>> USE_CASES = List.of(
            Map.of("id", "1", "title", "Session Management + Auth Token",
                    "description", "Authenticate clients and manage sessions with Redis Hash, JSON, and TTL",
                    "icon", "🔐", "features", "Hash · JSON · TTL"),
            Map.of("id", "2", "title", "Rate Limiting (Open Banking / PSD2)",
                    "description", "Protect APIs with sliding-window rate limiting using INCR and EXPIRE",
                    "icon", "⏱️", "features", "String · INCR · EXPIRE"),
            Map.of("id", "3", "title", "Transaction Deduplication",
                    "description", "Prevent duplicate transactions using Set, Bloom Filter, and Hash with TTL",
                    "icon", "🔁", "features", "Set · Bloom Filter · Hash · TTL"),
            Map.of("id", "4", "title", "Real-time Fraud Detection",
                    "description", "Evaluate transactions for fraud using Sorted Sets, Streams, and RQE",
                    "icon", "🛡️", "features", "Sorted Set · Streams · RQE"),
            Map.of("id", "5", "title", "Feature Store (Risk Scoring)",
                    "description", "Store and retrieve per-client risk features with Hash, TTL, and RQE",
                    "icon", "📊", "features", "Hash · TTL · RQE"),
            Map.of("id", "6", "title", "Document Search (Full-text + Vector)",
                    "description", "Search regulatory documents using full-text and vector similarity",
                    "icon", "🔍", "features", "Vector · RQE · JSON"),
            Map.of("id", "7", "title", "AI Banking Assistant (Memory + RAG)",
                    "description", "Conversational AI assistant with memory and retrieval-augmented generation",
                    "icon", "🤖", "features", "Hash · Vector · Streams · JSON · TTL")
    );

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("useCases", USE_CASES);
        return "index";
    }
}
