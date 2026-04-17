package com.redis.workshop.controller;

import com.redis.workshop.service.GeoFinderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/geo")
public class GeoFinderController {

    private final GeoFinderService geoFinderService;

    public GeoFinderController(GeoFinderService geoFinderService) {
        this.geoFinderService = geoFinderService;
    }

    /** Native GEOSEARCH — simple radius search. */
    @GetMapping("/search/native")
    public ResponseEntity<Map<String, Object>> searchNative(
            @RequestParam double lng,
            @RequestParam double lat,
            @RequestParam(defaultValue = "2") double radius) {
        return ResponseEntity.ok(geoFinderService.searchNative(lng, lat, radius));
    }

    /** JSON + Redis Query Engine — geo + type/service filters. */
    @GetMapping("/search/rqe")
    public ResponseEntity<Map<String, Object>> searchRQE(
            @RequestParam double lng,
            @RequestParam double lat,
            @RequestParam(defaultValue = "2") double radius,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String service) {
        return ResponseEntity.ok(geoFinderService.searchRQE(lng, lat, radius, type, service));
    }

    /** List all ATMs and branches. */
    @GetMapping("/branches")
    public ResponseEntity<?> listBranches() {
        return ResponseEntity.ok(geoFinderService.listAll());
    }
}
