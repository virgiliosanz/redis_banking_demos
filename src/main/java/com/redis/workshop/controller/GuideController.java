package com.redis.workshop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class GuideController {

    @GetMapping("/guide")
    public String guide() {
        return "guide";
    }
}
