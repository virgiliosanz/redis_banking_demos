package com.redis.workshop.controller;

import com.redis.workshop.service.FeatureStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/features")
public class FeatureStoreController {

    private final FeatureStoreService featureStoreService;

    public FeatureStoreController(FeatureStoreService featureStoreService) {
        this.featureStoreService = featureStoreService;
    }

    /** List all mock clients. */
    @GetMapping("/clients")
    public ResponseEntity<?> listClients() {
        return ResponseEntity.ok(featureStoreService.listClients());
    }

    /** Get all features for a client (HGETALL). */
    @GetMapping("/client/{clientId}")
    public ResponseEntity<?> getFeatures(@PathVariable String clientId,
                                         @RequestParam(required = false) String version) {
        Map<String, Object> features = featureStoreService.getFeatures(clientId, version);
        if (features == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Client not found: " + clientId));
        }
        return ResponseEntity.ok(features);
    }

    /** Run mock ML inference using versioned online features from Redis. */
    @GetMapping("/inference/{clientId}")
    public ResponseEntity<?> runInference(@PathVariable String clientId,
                                          @RequestParam(required = false) String version) {
        Map<String, Object> result = featureStoreService.runInference(clientId, version);
        if (result == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Client not found: " + clientId));
        }
        return ResponseEntity.ok(result);
    }

    /** Simulate a transaction for a client. */
    @PostMapping("/simulate")
    public ResponseEntity<?> simulateTransaction(@RequestBody Map<String, Object> body) {
        String clientId = (String) body.get("clientId");
        if (clientId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "clientId is required"));
        }

        double amount;
        try {
            amount = Double.parseDouble(body.get("amount").toString());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Valid amount is required"));
        }

        String country = body.getOrDefault("country", "ES").toString();

        Map<String, Object> result = featureStoreService.simulateTransaction(clientId, amount, country);
        return ResponseEntity.ok(result);
    }
}
