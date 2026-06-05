package com.redis.workshop.config;

import com.redis.workshop.service.AgentCoordinatorService;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("startupCleanup")
public class AgentCoordinatorDataLoader {

    private final AgentCoordinatorService agentCoordinatorService;

    public AgentCoordinatorDataLoader(AgentCoordinatorService agentCoordinatorService) {
        this.agentCoordinatorService = agentCoordinatorService;
    }

    @PostConstruct
    public void loadDemoData() {
        agentCoordinatorService.init();
    }
}