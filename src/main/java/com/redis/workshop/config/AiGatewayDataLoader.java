package com.redis.workshop.config;

import com.redis.workshop.service.AiGatewayService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("startupCleanup")
public class AiGatewayDataLoader {

    private final AiGatewayService aiGatewayService;

    @Value("${workshop.startup.load-data:true}")
    private boolean loadData;

    public AiGatewayDataLoader(AiGatewayService aiGatewayService) {
        this.aiGatewayService = aiGatewayService;
    }

    @PostConstruct
    public void loadDemoData() {
        if (!loadData) return;
        aiGatewayService.init();
        aiGatewayService.seedDemoData();
    }
}