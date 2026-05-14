package com.redis.workshop.config;

import com.redis.workshop.service.AiGatewayService;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("startupCleanup")
public class AiGatewayDataLoader {

    private final AiGatewayService aiGatewayService;

    public AiGatewayDataLoader(AiGatewayService aiGatewayService) {
        this.aiGatewayService = aiGatewayService;
    }

    @PostConstruct
    public void loadDemoData() {
        aiGatewayService.init();
        aiGatewayService.seedDemoData();
    }
}