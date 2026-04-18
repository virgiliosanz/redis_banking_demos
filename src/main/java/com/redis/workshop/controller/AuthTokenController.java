package com.redis.workshop.controller;

import com.redis.workshop.service.AuthTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for UC1: Authentication Token Store.
 * Demonstrates token generation, storage in Redis Hash with TTL,
 * validation, and logout (deletion).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthTokenController {

    private final AuthTokenService authTokenService;

    public AuthTokenController(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    /**
     * Login: validate credentials, generate token, store in Redis Hash with TTL.
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (username == null || password == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username and password are required"));
        }

        Map<String, Object> result = authTokenService.login(username, password);
        if (result == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid credentials"));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Validate: check if token exists in Redis.
     * POST /api/auth/validate
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Token is required"));
        }

        Map<String, Object> result = authTokenService.validateToken(token);
        if (result == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("valid", false, "error", "Token expired or invalid"));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Logout: delete token from Redis.
     * POST /api/auth/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Token is required"));
        }

        Map<String, Object> logoutResult = authTokenService.logout(token);
        boolean deleted = Boolean.TRUE.equals(logoutResult.get("deleted"));
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("success", deleted);
        response.put("message", deleted ? "Token destroyed" : "Token not found or already expired");
        response.put("redisKey", logoutResult.get("redisKey"));
        response.put("redisCommands", logoutResult.get("redisCommands"));
        return ResponseEntity.ok(response);
    }

    /**
     * Get token info (for demo inspection).
     * GET /api/auth/token/{token}
     */
    @GetMapping("/token/{token}")
    public ResponseEntity<?> getTokenInfo(@PathVariable String token) {
        Map<String, Object> info = authTokenService.validateToken(token);
        if (info == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Token not found or expired"));
        }
        return ResponseEntity.ok(info);
    }
}
