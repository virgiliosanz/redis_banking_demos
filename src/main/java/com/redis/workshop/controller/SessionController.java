package com.redis.workshop.controller;

import com.redis.workshop.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (username == null || password == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username and password are required"));
        }

        Map<String, Object> session = sessionService.login(username, password);
        if (session == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid credentials"));
        }

        return ResponseEntity.ok(session);
    }

    @GetMapping("/info/{username}")
    public ResponseEntity<?> getSession(@PathVariable String username) {
        Map<String, Object> session = sessionService.getSession(username);
        if (session == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "No active session"));
        }
        return ResponseEntity.ok(session);
    }

    @GetMapping("/ttl/{username}")
    public ResponseEntity<?> getTtl(@PathVariable String username) {
        long ttl = sessionService.getSessionTtl(username);
        return ResponseEntity.ok(Map.of("ttl", ttl, "username", username));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Username is required"));
        }

        Map<String, Object> logoutResult = sessionService.logout(username);
        boolean deleted = Boolean.TRUE.equals(logoutResult.get("deleted"));
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("success", deleted);
        response.put("message", deleted ? "Session destroyed" : "No active session");
        response.put("sessionKey", logoutResult.get("sessionKey"));
        response.put("redisCommands", logoutResult.get("redisCommands"));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Token is required"));
        }

        String username = sessionService.validateToken(token);
        if (username == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("valid", false, "error", "Token expired or invalid"));
        }

        return ResponseEntity.ok(Map.of("valid", true, "username", username));
    }
}
