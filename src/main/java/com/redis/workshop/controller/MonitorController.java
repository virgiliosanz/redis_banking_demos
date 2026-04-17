package com.redis.workshop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MonitorController {

    @GetMapping("/monitor")
    public String monitor() {
        return "monitor";
    }
}
