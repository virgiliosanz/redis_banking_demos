package com.redis.workshop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import com.redis.workshop.config.RedisSearchHelper;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@DependsOn("startupCleanup")
public class GeoFinderService {

    private static final Logger log = LoggerFactory.getLogger(GeoFinderService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisSearchHelper redisSearchHelper;

    private static final String GEO_KEY = "workshop:geo:atms";
    private static final String META_PREFIX = "workshop:geo:meta:";
    private static final String BRANCH_PREFIX = "workshop:geo:branch:";
    private static final String INDEX_NAME = "idx:branches";

    public GeoFinderService(StringRedisTemplate redis, ObjectMapper objectMapper, RedisSearchHelper redisSearchHelper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisSearchHelper = redisSearchHelper;
    }

    @PostConstruct
    public void init() {
        loadBranches();
        createIndex();
    }

    @SuppressWarnings("unchecked")
    private void loadBranches() {
        List<Map<String, Object>> branches = getBranchData();

        for (var branch : branches) {
            double lng = ((Number) branch.get("lng")).doubleValue();
            double lat = ((Number) branch.get("lat")).doubleValue();
            String id = (String) branch.get("id");

            // Approach 1: GEOADD + Hash metadata
            redis.opsForGeo().add(GEO_KEY, new Point(lng, lat), id);
            String metaKey = META_PREFIX + id;
            Map<String, String> meta = new HashMap<>();
            meta.put("name", (String) branch.get("name"));
            meta.put("type", (String) branch.get("type"));
            meta.put("address", (String) branch.get("address"));
            meta.put("hours", (String) branch.get("hours"));
            meta.put("services", String.join(",", (List<String>) branch.get("services")));
            meta.put("lat", String.valueOf(lat));
            meta.put("lng", String.valueOf(lng));
            redis.opsForHash().putAll(metaKey, meta);

            // Approach 2: JSON.SET for RQE
            try {
                String jsonKey = BRANCH_PREFIX + id;
                Map<String, Object> jsonDoc = new LinkedHashMap<>(branch);
                jsonDoc.put("location", lng + "," + lat);
                String json = objectMapper.writeValueAsString(jsonDoc);
                redis.execute(connection -> {
                    connection.execute("JSON.SET",
                            jsonKey.getBytes(StandardCharsets.UTF_8),
                            "$".getBytes(StandardCharsets.UTF_8),
                            json.getBytes(StandardCharsets.UTF_8));
                    return null;
                }, true);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize branch {}", id, e);
            }
        }
        log.info("Loaded {} ATMs/branches into Redis (Geo + JSON)", branches.size());
    }

    private void createIndex() {
        try {
            redis.execute(connection -> {
                connection.execute("FT.DROPINDEX",
                        INDEX_NAME.getBytes(StandardCharsets.UTF_8));
                return null;
            }, true);
        } catch (Exception ignored) { }

        try {
            redis.execute(connection -> {
                connection.execute("FT.CREATE",
                        INDEX_NAME.getBytes(StandardCharsets.UTF_8),
                        "ON".getBytes(StandardCharsets.UTF_8),
                        "JSON".getBytes(StandardCharsets.UTF_8),
                        "PREFIX".getBytes(StandardCharsets.UTF_8),
                        "1".getBytes(StandardCharsets.UTF_8),
                        BRANCH_PREFIX.getBytes(StandardCharsets.UTF_8),
                        "SCHEMA".getBytes(StandardCharsets.UTF_8),
                        "$.location".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "location".getBytes(StandardCharsets.UTF_8),
                        "GEO".getBytes(StandardCharsets.UTF_8),
                        "$.type".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "type".getBytes(StandardCharsets.UTF_8),
                        "TAG".getBytes(StandardCharsets.UTF_8),
                        "$.services[*]".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "services".getBytes(StandardCharsets.UTF_8),
                        "TAG".getBytes(StandardCharsets.UTF_8),
                        "$.name".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "name".getBytes(StandardCharsets.UTF_8),
                        "TEXT".getBytes(StandardCharsets.UTF_8),
                        "$.hours".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "hours".getBytes(StandardCharsets.UTF_8),
                        "TAG".getBytes(StandardCharsets.UTF_8));
                return null;
            }, true);
            log.info("Created index {}", INDEX_NAME);
        } catch (Exception e) {
            log.warn("Index {} may already exist: {}", INDEX_NAME, e.getMessage());
        }
    }


    /** Approach 1: Native GEOSEARCH */
    public Map<String, Object> searchNative(double lng, double lat, double radiusKm) {
        long start = System.nanoTime();

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = redis.opsForGeo()
                .search(GEO_KEY,
                        GeoReference.fromCoordinate(lng, lat),
                        new Distance(radiusKm, Metrics.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(20));

        List<Map<String, Object>> locations = new ArrayList<>();
        if (geoResults != null) {
            for (var result : geoResults) {
                String id = result.getContent().getName();
                Map<Object, Object> meta = redis.opsForHash().entries(META_PREFIX + id);
                Map<String, Object> loc = new LinkedHashMap<>();
                loc.put("id", id);
                loc.put("distance", Math.round(result.getDistance().getValue() * 1000.0) / 1000.0);
                loc.put("distanceUnit", "km");
                loc.put("name", meta.getOrDefault("name", ""));
                loc.put("type", meta.getOrDefault("type", ""));
                loc.put("address", meta.getOrDefault("address", ""));
                loc.put("hours", meta.getOrDefault("hours", ""));
                loc.put("lat", Double.parseDouble((String) meta.getOrDefault("lat", "0")));
                loc.put("lng", Double.parseDouble((String) meta.getOrDefault("lng", "0")));
                String svc = (String) meta.getOrDefault("services", "");
                loc.put("services", svc.isEmpty() ? List.of() : List.of(svc.split(",")));
                locations.add(loc);
            }
        }

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        String command = "GEOSEARCH " + GEO_KEY + " FROMLONLAT " + lng + " " + lat
                + " BYRADIUS " + radiusKm + " km ASC COUNT 20";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", locations);
        response.put("count", locations.size());
        response.put("latencyMs", latencyMs);
        response.put("approach", "Native Geospatial");
        response.put("command", command);
        return response;
    }

    /** Approach 2: JSON + Redis Query Engine */
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchRQE(double lng, double lat, double radiusKm,
                                          String type, String service) {
        long start = System.nanoTime();

        StringBuilder query = new StringBuilder();
        query.append("@location:[").append(lng).append(" ").append(lat)
                .append(" ").append(radiusKm).append(" km]");
        if (type != null && !type.isEmpty() && !"all".equalsIgnoreCase(type)) {
            query.append(" @type:{").append(type).append("}");
        }
        if (service != null && !service.isEmpty() && !"all".equalsIgnoreCase(service)) {
            query.append(" @services:{").append(service).append("}");
        }

        String ftQuery = query.toString();

        List<Object> rawResults = executeFtSearch(INDEX_NAME, ftQuery, "LIMIT", "0", "20");

        List<Map<String, Object>> results = parseRqeResults(rawResults);

        // Calculate distances (RQE geo filter doesn't return distance)
        for (var r : results) {
            double rLat = ((Number) r.getOrDefault("lat", 0.0)).doubleValue();
            double rLng = ((Number) r.getOrDefault("lng", 0.0)).doubleValue();
            r.put("distance", Math.round(haversine(lat, lng, rLat, rLng) * 1000.0) / 1000.0);
            r.put("distanceUnit", "km");
        }
        results.sort(Comparator.comparingDouble(a -> ((Number) a.get("distance")).doubleValue()));

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        String command = "FT.SEARCH " + INDEX_NAME + " \"" + ftQuery + "\"";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("count", results.size());
        response.put("latencyMs", latencyMs);
        response.put("approach", "JSON + Query Engine");
        response.put("command", command);
        return response;
    }

    /** List all branches/ATMs */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (var branch : getBranchData()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", branch.get("id"));
            item.put("name", branch.get("name"));
            item.put("type", branch.get("type"));
            item.put("address", branch.get("address"));
            item.put("lat", branch.get("lat"));
            item.put("lng", branch.get("lng"));
            item.put("services", branch.get("services"));
            item.put("hours", branch.get("hours"));
            all.add(item);
        }
        return all;
    }

    // --- Helpers ---

    private List<Object> executeFtSearch(String indexName, String query, String... extraArgs) {
        return redisSearchHelper.ftSearchRaw(indexName, query, extraArgs);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseRqeResults(Object rawResults) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (rawResults == null) return results;

        List<Object> list;
        if (rawResults instanceof List<?> l) {
            list = (List<Object>) l;
        } else {
            return results;
        }
        if (list.size() < 2) return results;

        for (int i = 1; i < list.size(); i += 2) {
            if (i + 1 >= list.size()) break;
            String docKey = RedisSearchHelper.toStr(list.get(i));
            Object fieldsObj = list.get(i + 1);
            if (!(fieldsObj instanceof List<?> fields)) continue;

            Map<String, Object> doc = new LinkedHashMap<>();
            String id = docKey.startsWith(BRANCH_PREFIX)
                    ? docKey.substring(BRANCH_PREFIX.length()) : docKey;
            doc.put("id", id);

            for (int j = 0; j + 1 < fields.size(); j += 2) {
                String fn = RedisSearchHelper.toStr(fields.get(j));
                String fv = RedisSearchHelper.toStr(fields.get(j + 1));
                // JSON fields come as $. path with JSON arrays
                if ("$".equals(fn)) {
                    try {
                        Map<String, Object> json = objectMapper.readValue(fv, LinkedHashMap.class);
                        doc.put("name", json.getOrDefault("name", ""));
                        doc.put("type", json.getOrDefault("type", ""));
                        doc.put("address", json.getOrDefault("address", ""));
                        doc.put("hours", json.getOrDefault("hours", ""));
                        doc.put("lat", ((Number) json.getOrDefault("lat", 0.0)).doubleValue());
                        doc.put("lng", ((Number) json.getOrDefault("lng", 0.0)).doubleValue());
                        doc.put("services", json.getOrDefault("services", List.of()));
                    } catch (Exception e) {
                        log.warn("Failed to parse JSON for {}", docKey, e);
                    }
                }
            }
            results.add(doc);
        }
        return results;
    }



    /** Haversine formula — distance in km */
    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Static branch data — 18 ATMs and branches in Madrid */
    private static List<Map<String, Object>> getBranchData() {
        return List.of(
            Map.of("id", "atm-001", "name", "Cajero Sol", "type", "atm",
                   "address", "Puerta del Sol 1, Madrid", "lat", 40.4168, "lng", -3.7038,
                   "services", List.of("withdrawal", "deposit", "balance"), "hours", "24h"),
            Map.of("id", "atm-002", "name", "Cajero Gran Vía", "type", "atm",
                   "address", "Gran Vía 32, Madrid", "lat", 40.4200, "lng", -3.7056,
                   "services", List.of("withdrawal", "balance"), "hours", "24h"),
            Map.of("id", "branch-001", "name", "Sucursal Cibeles", "type", "branch",
                   "address", "Plaza de Cibeles 4, Madrid", "lat", 40.4194, "lng", -3.6931,
                   "services", List.of("withdrawal", "deposit", "advisor", "mortgage", "business"), "hours", "9:00-14:00"),
            Map.of("id", "atm-003", "name", "Cajero Atocha", "type", "atm",
                   "address", "Estación de Atocha, Madrid", "lat", 40.4065, "lng", -3.6895,
                   "services", List.of("withdrawal", "deposit"), "hours", "24h"),
            Map.of("id", "branch-002", "name", "Sucursal Castellana", "type", "branch",
                   "address", "Paseo de la Castellana 79, Madrid", "lat", 40.4372, "lng", -3.6920,
                   "services", List.of("withdrawal", "deposit", "advisor", "insurance"), "hours", "8:30-14:30"),
            Map.of("id", "atm-004", "name", "Cajero Retiro", "type", "atm",
                   "address", "C/ Alcalá 102, Madrid", "lat", 40.4225, "lng", -3.6832,
                   "services", List.of("withdrawal"), "hours", "24h"),
            Map.of("id", "branch-003", "name", "Sucursal Chamberí", "type", "branch",
                   "address", "C/ Santa Engracia 45, Madrid", "lat", 40.4340, "lng", -3.7015,
                   "services", List.of("withdrawal", "deposit", "advisor"), "hours", "9:00-14:00"),
            Map.of("id", "atm-005", "name", "Cajero Malasaña", "type", "atm",
                   "address", "C/ Fuencarral 78, Madrid", "lat", 40.4266, "lng", -3.7032,
                   "services", List.of("withdrawal", "deposit", "balance"), "hours", "24h"),
            Map.of("id", "atm-006", "name", "Cajero Lavapiés", "type", "atm",
                   "address", "C/ Argumosa 3, Madrid", "lat", 40.4089, "lng", -3.7006,
                   "services", List.of("withdrawal"), "hours", "24h"),
            Map.of("id", "branch-004", "name", "Sucursal Salamanca", "type", "branch",
                   "address", "C/ Serrano 25, Madrid", "lat", 40.4270, "lng", -3.6860,
                   "services", List.of("withdrawal", "deposit", "advisor", "private_banking"), "hours", "9:00-14:30"),
            Map.of("id", "atm-007", "name", "Cajero Moncloa", "type", "atm",
                   "address", "C/ Princesa 40, Madrid", "lat", 40.4310, "lng", -3.7180,
                   "services", List.of("withdrawal", "deposit"), "hours", "24h"),
            Map.of("id", "branch-005", "name", "Sucursal Tetuán", "type", "branch",
                   "address", "C/ Bravo Murillo 120, Madrid", "lat", 40.4505, "lng", -3.7040,
                   "services", List.of("withdrawal", "deposit", "advisor", "business"), "hours", "8:30-14:00"),
            Map.of("id", "atm-008", "name", "Cajero Bernabéu", "type", "atm",
                   "address", "Paseo de la Castellana 104, Madrid", "lat", 40.4530, "lng", -3.6883,
                   "services", List.of("withdrawal", "deposit", "balance"), "hours", "24h"),
            Map.of("id", "atm-009", "name", "Cajero Callao", "type", "atm",
                   "address", "Plaza del Callao 2, Madrid", "lat", 40.4198, "lng", -3.7065,
                   "services", List.of("withdrawal", "deposit"), "hours", "24h"),
            Map.of("id", "branch-006", "name", "Sucursal Argüelles", "type", "branch",
                   "address", "C/ Alberto Aguilera 16, Madrid", "lat", 40.4295, "lng", -3.7120,
                   "services", List.of("withdrawal", "deposit", "advisor"), "hours", "9:00-14:00"),
            Map.of("id", "atm-010", "name", "Cajero Tribunal", "type", "atm",
                   "address", "C/ Hortaleza 25, Madrid", "lat", 40.4243, "lng", -3.6988,
                   "services", List.of("withdrawal", "balance"), "hours", "24h"),
            Map.of("id", "atm-011", "name", "Cajero Ópera", "type", "atm",
                   "address", "Plaza de Ópera 6, Madrid", "lat", 40.4180, "lng", -3.7098,
                   "services", List.of("withdrawal", "deposit"), "hours", "24h"),
            Map.of("id", "branch-007", "name", "Sucursal Centro", "type", "branch",
                   "address", "C/ Mayor 18, Madrid", "lat", 40.4155, "lng", -3.7078,
                   "services", List.of("withdrawal", "deposit", "advisor", "mortgage", "insurance"), "hours", "9:00-15:00")
        );
    }
}