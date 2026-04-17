package com.redis.workshop.controller;

import com.redis.workshop.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for UC3: User Profile Storage.
 * Aggregates profile data from multiple mock databases into a single Redis Hash.
 */
@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /**
     * Load user profile by aggregating from multiple mock DBs into Redis.
     * POST /api/profile/load/{userId}
     */
    @PostMapping("/load/{userId}")
    public ResponseEntity<?> loadProfile(@PathVariable String userId) {
        Map<String, Object> result = userProfileService.loadProfile(userId);
        if (result == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "User not found: " + userId));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Get aggregated profile from Redis.
     * GET /api/profile/{userId}
     */
    @GetMapping("/{userId}")
    public ResponseEntity<?> getProfile(@PathVariable String userId) {
        Map<String, Object> profile = userProfileService.getProfile(userId);
        if (profile == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Profile not loaded. Use /load first."));
        }
        return ResponseEntity.ok(profile);
    }

    /**
     * Update profile fields.
     * PUT /api/profile/{userId}
     */
    @PostMapping("/update/{userId}")
    public ResponseEntity<?> updateProfile(@PathVariable String userId,
                                           @RequestBody Map<String, String> updates) {
        Map<String, Object> result = userProfileService.updateProfile(userId, updates);
        if (result == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Profile not loaded. Use /load first."));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Sync profile changes back to mock DBs.
     * POST /api/profile/sync/{userId}
     */
    @PostMapping("/sync/{userId}")
    public ResponseEntity<?> syncProfile(@PathVariable String userId) {
        Map<String, Object> result = userProfileService.syncProfile(userId);
        if (result == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Profile not loaded. Use /load first."));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * List available users.
     * GET /api/profile/users
     */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers() {
        return ResponseEntity.ok(userProfileService.listUsers());
    }
}
