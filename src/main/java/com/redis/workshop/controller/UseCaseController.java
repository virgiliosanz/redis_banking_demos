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

    private static final Map<Integer, String> TITLES = Map.of(
            1, "Session Management + Auth Token",
            2, "Rate Limiting (Open Banking / PSD2)",
            3, "Transaction Deduplication",
            4, "Real-time Fraud Detection",
            5, "Feature Store (Risk Scoring)",
            6, "Document Search (Full-text + Vector)",
            7, "AI Banking Assistant (Memory + RAG)"
    );

    @GetMapping("/{id}")
    public String useCase(@PathVariable int id, Model model) {
        if (id < 1 || id > 7) {
            return "redirect:/";
        }
        model.addAttribute("useCaseId", id);
        model.addAttribute("useCaseTitle", TITLES.get(id));
        return "usecase-" + id;
    }
}
