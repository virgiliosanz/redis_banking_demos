package com.redis.workshop.controller;

import com.redis.workshop.config.DocumentDataLoader;
import com.redis.workshop.config.StartupCleanup;
import com.redis.workshop.service.AssistantService;
import com.redis.workshop.service.AgentCoordinatorService;
import com.redis.workshop.service.AiGatewayService;
import com.redis.workshop.service.CacheAsideService;
import com.redis.workshop.service.FeatureStoreService;
import com.redis.workshop.service.FraudService;
import com.redis.workshop.service.GuardrailsService;
import com.redis.workshop.service.GeoFinderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workshop-wide admin operations. Exposes a single reset endpoint that
 * flushes Redis and re-runs every demo data loader, so presenters can
 * return to a clean state between sessions without restarting the app.
 */
@RestController
@RequestMapping("/api")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final StringRedisTemplate redis;
    private final StartupCleanup startupCleanup;
    private final DocumentDataLoader documentDataLoader;
    private final AssistantService assistantService;
    private final FraudService fraudService;
    private final GeoFinderService geoFinderService;
    private final FeatureStoreService featureStoreService;
    private final CacheAsideService cacheAsideService;
    private final GuardrailsService guardrailsService;
    private final AiGatewayService aiGatewayService;
    private final AgentCoordinatorService agentCoordinatorService;

    public AdminController(StringRedisTemplate redis,
                           StartupCleanup startupCleanup,
                           DocumentDataLoader documentDataLoader,
                           AssistantService assistantService,
                           FraudService fraudService,
                           GeoFinderService geoFinderService,
                           FeatureStoreService featureStoreService,
                           CacheAsideService cacheAsideService,
                           GuardrailsService guardrailsService,
                           AiGatewayService aiGatewayService,
                           AgentCoordinatorService agentCoordinatorService) {
        this.redis = redis;
        this.startupCleanup = startupCleanup;
        this.documentDataLoader = documentDataLoader;
        this.assistantService = assistantService;
        this.fraudService = fraudService;
        this.geoFinderService = geoFinderService;
        this.featureStoreService = featureStoreService;
        this.cacheAsideService = cacheAsideService;
        this.guardrailsService = guardrailsService;
        this.aiGatewayService = aiGatewayService;
        this.agentCoordinatorService = agentCoordinatorService;
    }

    /**
     * Reset all demo data: scoped workshop cleanup + re-run every demo loader.
     * Supports both the legacy {@code /api/reset-all} path and the explicit
     * {@code /api/admin/reload} alias used for force reload automation.
     */
    @PostMapping({"/reset-all", "/admin/reload"})
    public ResponseEntity<Map<String, Object>> resetAll() {
        long overallStart = System.currentTimeMillis();
        List<Map<String, Object>> steps = new ArrayList<>();
        boolean ok = true;

        ok &= runStep(steps, "StartupCleanup.cleanupWorkshopState", () -> startupCleanup.cleanupWorkshopState());

        ok &= runStep(steps, "DocumentDataLoader.loadDocuments", documentDataLoader::loadDocuments);
        ok &= runStep(steps, "AssistantService.init", assistantService::init);
        ok &= runStep(steps, "FraudService.loadBaselineData", fraudService::loadBaselineData);
        ok &= runStep(steps, "GeoFinderService.init", geoFinderService::init);
        ok &= runStep(steps, "FeatureStoreService.loadInitialFeatures", featureStoreService::loadInitialFeatures);
        ok &= runStep(steps, "CacheAsideService.init", cacheAsideService::init);
        ok &= runStep(steps, "GuardrailsService.init", guardrailsService::init);
        ok &= runStep(steps, "AiGatewayService.init", aiGatewayService::init);
        ok &= runStep(steps, "AiGatewayService.seedDemoData", aiGatewayService::seedDemoData);
        ok &= runStep(steps, "AgentCoordinatorService.reset", agentCoordinatorService::reset);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "ok" : "partial");
        body.put("totalMs", System.currentTimeMillis() - overallStart);
        body.put("steps", steps);
        return ok ? ResponseEntity.ok(body) : ResponseEntity.status(500).body(body);
    }

    private boolean runStep(List<Map<String, Object>> steps, String name, Runnable action) {
        long start = System.currentTimeMillis();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        try {
            action.run();
            entry.put("status", "ok");
            entry.put("durationMs", System.currentTimeMillis() - start);
            steps.add(entry);
            return true;
        } catch (Exception e) {
            log.error("Reset step '{}' failed", name, e);
            entry.put("status", "error");
            entry.put("durationMs", System.currentTimeMillis() - start);
            entry.put("error", e.getMessage());
            steps.add(entry);
            return false;
        }
    }
}
