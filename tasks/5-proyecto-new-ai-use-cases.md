# Proyecto 5: New AI Use Cases (UC15, UC16) + UC7 Enhancement

## Estado: Cerrado

## Resumen
Added 2 new AI-focused use cases and enhanced UC7 for the Redis Banking Workshop:
- **UC7 Enhancement**: Feature Store now includes ML inference simulation with latency breakdown
- **UC15**: AI Guardrails for Banking Chat — 6-stage pipeline (rate limit, topic classification, PII detection, prompt injection, output PII, compliance)
- **UC16**: AI Gateway — Semantic routing, per-model rate limiting, semantic caching, request/response logging

## Fase 1: Implementation (Wave 1)
- UC7 enhanced with ML inference pipeline, model metadata, batch scoring
- UC15 implemented with vector-based topic routing, prompt injection detection via RQE, PII scrubbing, audit trail via Streams
- UC16 implemented with semantic routing via vector search, semantic cache, per-model rate limiting, observability via Streams
- All using mock data (no real LLM needed)

## Fase 2: Integration (Wave 2)
- Landing page updated to 16 cards
- Navigation updated with UC15/UC16 links
- Presenter guide updated
- Tests updated for new endpoints

## Bugs Fixed
- Code showcase tabs not switching (Prism.js timing issue) — pre-existing bug affecting all UCs
- Vector indices not surviving reset-all — init logic added to AdminController
- RedisInsight moved from workshop profile to default startup

## Decisiones
- Mock vectors for topic routing and prompt injection (same pattern as existing UCs)
- UC15 uses Redis Streams for audit trail (real-time pipeline visibility)
- UC16 semantic cache is independent from UC9's cache (different key prefix uc16:)
- Scoped cleanup with per-UC key prefixes instead of FLUSHALL

## Validacion
- ./mvnw compile ✅
- ./mvnw test ✅
- All 16 UC pages load correctly ✅
- UC15 all 5 scenarios verified (balance+PII, investment, support, blocked topic, prompt injection) ✅
- UC16 gateway routing + caching verified ✅
- Code showcase tabs work on all UCs ✅
- RedisInsight starts by default on port 5540 ✅

## Ficheros nuevos
- src/main/java/com/redis/workshop/controller/GuardrailsController.java
- src/main/java/com/redis/workshop/controller/AiGatewayController.java
- src/main/java/com/redis/workshop/service/GuardrailsService.java
- src/main/java/com/redis/workshop/service/AiGatewayService.java
- src/main/java/com/redis/workshop/config/AiGatewayDataLoader.java
- src/main/resources/templates/usecase-15.html
- src/main/resources/templates/usecase-16.html
- src/main/resources/static/js/usecase-15.js
- src/main/resources/static/js/usecase-16.js

## Ficheros modificados
- src/main/java/com/redis/workshop/controller/FeatureStoreController.java (UC7 enhancement)
- src/main/java/com/redis/workshop/controller/UseCaseController.java (UC15/16 routing)
- src/main/resources/templates/layout.html (navigation)
- src/main/resources/templates/guide.html (presenter guide)
- src/main/resources/static/js/main.js (code tabs fix)
- src/test/java/com/redis/workshop/controller/ApiEndpointTests.java
- src/test/java/com/redis/workshop/controller/UseCaseEndpointTests.java
- docker-compose.yml (RedisInsight default startup)
- README.md (documentation update)