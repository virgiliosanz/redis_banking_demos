package com.redis.workshop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class GuideController {

    @GetMapping("/guide")
    public String guide(Model model) {
        model.addAttribute("pageTitle", "Workshop Guide");
        model.addAttribute("pageDescription", "Presenter guide for the Redis Banking Workshop with demo steps, Redis commands, and talking points for all use cases.");
        return "guide";
    }

    @GetMapping("/monitor")
    public String monitor(Model model) {
        model.addAttribute("pageTitle", "Monitor Dashboard");
        model.addAttribute("pageDescription", "Live Redis server metrics and command stream for the Redis Banking Workshop demo environment.");
        return "monitor";
    }
}
