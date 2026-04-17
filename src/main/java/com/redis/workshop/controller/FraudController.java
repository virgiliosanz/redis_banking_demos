package com.redis.workshop.controller;

import com.redis.workshop.service.FraudService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudService fraudService;

    public FraudController(FraudService fraudService) {
        this.fraudService = fraudService;
    }

    /**
     * Evaluate a transaction for fraud risk.
     * POST /api/fraud/evaluate
     * Body: { "cardNumber": "...", "amount": "...", "merchant": "...", "country": "..." }
     */
    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@RequestBody Map<String, String> request) {
        String cardNumber = request.getOrDefault("cardNumber", "");
        String amountStr = request.getOrDefault("amount", "0");
        String merchant = request.getOrDefault("merchant", "");
        String country = request.getOrDefault("country", "");

        if (cardNumber.isBlank() || merchant.isBlank() || country.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "cardNumber, merchant, and country are required"
            ));
        }

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "Invalid amount"
            ));
        }

        Map<String, Object> result = fraudService.evaluateTransaction(cardNumber, amount, merchant, country);
        return ResponseEntity.ok(result);
    }

    /**
     * Get the recent fraud evaluation stream.
     * GET /api/fraud/stream
     */
    @GetMapping("/stream")
    public ResponseEntity<List<Map<String, Object>>> getStream(
            @RequestParam(defaultValue = "20") int count) {
        return ResponseEntity.ok(fraudService.getRecentStream(count));
    }

    /**
     * Reset fraud demo data.
     * POST /api/fraud/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        fraudService.reset();
        return ResponseEntity.ok(Map.of("status", "OK", "message", "Fraud demo reset complete"));
    }
}
