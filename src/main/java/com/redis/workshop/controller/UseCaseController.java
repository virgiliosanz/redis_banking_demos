package com.redis.workshop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@Controller
@RequestMapping("/usecase")
public class UseCaseController {

    private static final Map<Integer, String> TITLES = Map.ofEntries(
            Map.entry(1, "Authentication Token Store"),
            Map.entry(2, "Session Storage"),
            Map.entry(3, "User Profile Storage"),
            Map.entry(4, "Rate Limiting (Open Banking / PSD2)"),
            Map.entry(5, "Transaction Deduplication"),
            Map.entry(6, "Real-time Fraud Detection"),
            Map.entry(7, "Feature Store (Risk Scoring)"),
            Map.entry(8, "Document Database (Full-text + Vector)"),
            Map.entry(9, "AI Agent Memory + RAG"),
            Map.entry(10, "Cache-Aside Pattern"),
            Map.entry(11, "Real-time Transaction Monitoring"),
            Map.entry(12, "ATM & Branch Finder (Geospatial)"),
            Map.entry(13, "Distributed Locking")
    );

    @GetMapping("/{id}")
    public String useCase(@PathVariable int id, Model model) {
        if (!TITLES.containsKey(id)) {
            return "redirect:/";
        }
        model.addAttribute("useCaseId", id);
        model.addAttribute("useCaseTitle", TITLES.get(id));
        return "usecase-" + id;
    }
}
