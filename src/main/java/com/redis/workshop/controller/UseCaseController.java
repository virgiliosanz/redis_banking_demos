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
            1, "Authentication Token Store",
            2, "Session Storage",
            3, "User Profile Storage",
            4, "Rate Limiting (Open Banking / PSD2)",
            5, "Transaction Deduplication",
            6, "Real-time Fraud Detection",
            7, "Feature Store (Risk Scoring)",
            8, "Document Database (Full-text + Vector)",
            9, "AI Agent Memory + RAG"
    );

    @GetMapping("/{id}")
    public String useCase(@PathVariable int id, Model model) {
        if (id < 1 || id > 9) {
            return "redirect:/";
        }
        model.addAttribute("useCaseId", id);
        model.addAttribute("useCaseTitle", TITLES.get(id));
        return "usecase-" + id;
    }
}
