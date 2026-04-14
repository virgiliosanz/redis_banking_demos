# Redis Banking Workshop — Presenter Guide

## Overview
Duration: ~2 hours
Audience: Technical architects and developers from banking sector
Format: Live demo with Redis, Spring Boot application

## Setup
```bash
docker compose --profile workshop up -d --build
# Open http://localhost:8080
```

---

## UC1: Authentication Token Store

### What to say
"When a user authenticates, we need to store their token somewhere fast and reliable. Redis is perfect for this — we store the token as a Hash with a TTL, so it auto-expires when the session ends. Any microservice in your architecture can validate the user by checking Redis."

### Demo steps
1. Click "Login" with the demo credentials
2. Show the generated token and its TTL countdown
3. Click "Validate Token" — show it returns valid
4. Wait or click "Logout" — show the token is gone
5. Try to validate again — show it fails

### Key Redis commands to highlight
- `HSET workshop:auth:token:{id} ...` — store token
- `EXPIRE` — auto-cleanup
- `HGET` / `EXISTS` — validate token

### Talking points
- Sub-millisecond token validation vs database lookup
- Distributed: any service can check the token
- TTL = automatic session expiry, no cleanup jobs needed

---

## UC2: Session Storage

### What to say
"Once authenticated, we need to store session state — form data, user preferences, recent actions. Instead of keeping this in the app server (which doesn't scale) or hitting the database on every request, we use Redis as a distributed session store."

### Demo steps
1. Create a new session — show data loaded from "RDBMS"
2. Modify session data (update a field)
3. Show the session in Redis with all fields
4. End the session — data "written back" to DB

### Key Redis commands to highlight
- `HSET` / `HGETALL` — session CRUD
- `EXPIRE` — auto-cleanup on inactivity

### Talking points
- Stateless app servers: any instance can serve any user
- Redis vs sticky sessions: horizontal scaling
- TTL handles abandoned sessions automatically

---

## UC3: User Profile Storage

### What to say
"In banking, a user profile is scattered across multiple systems — accounts, activity logs, preferences. Redis aggregates all of this into a single fast-access profile. When the user logs in, one call to Redis gives you everything."

### Demo steps
1. Select a user (e.g., "María García")
2. Click "Load Profile" — show data aggregated from Accounts DB, Activity DB, Preferences DB
3. Modify a field (e.g., notification preference)
4. Click "Sync Back" — changes written to source databases

### Key Redis commands to highlight
- `HSET` with multiple fields — aggregated profile
- `HGETALL` — single read for complete profile
- Pipeline for multi-DB aggregation

### Talking points
- Single source of truth during the session
- 60x faster than querying 3 different databases
- Write-back pattern for data consistency

---

## UC4: Rate Limiting

### What to say
"With PSD2 and Open Banking, your APIs are exposed to third parties. You need to protect them. Redis makes rate limiting trivial — INCR a counter, check the limit, set a TTL. It's atomic and fast."

### Demo steps
1. Click "Send Request" a few times — see counter increment
2. Hit the limit — see the request denied in red
3. Wait for the window to reset — see it allow again
4. Show the sliding window vs fixed window difference

### Key Redis commands to highlight
- `INCR` — atomic counter
- `EXPIRE` — window reset
- `ZADD` + `ZRANGEBYSCORE` — sliding window

### Talking points
- Atomic operations = no race conditions under load
- Multiple strategies: fixed window, sliding window, token bucket
- Per-user, per-API-key, per-IP — flexible key design


---

## UC5: Transaction Deduplication

### What to say
"Duplicate payments are a real problem in banking — network retries, user double-clicks, system failures. Redis gives you instant dedup with SET NX (set if not exists) and Bloom filters for memory-efficient duplicate checking."

### Demo steps
1. Submit a transaction — see it accepted
2. Submit the same transaction again — see it rejected as duplicate
3. Submit a different transaction — accepted
4. Show the Bloom filter approach for high-volume scenarios

### Key Redis commands to highlight
- `SET key value NX EX ttl` — atomic check-and-set
- `BF.ADD` / `BF.EXISTS` — probabilistic dedup

### Talking points
- SETNX is atomic — impossible to have two transactions slip through
- Bloom filter: 99.97% accurate with fraction of the memory
- TTL controls the dedup window (e.g., 24 hours)

---

## UC6: Fraud Detection (Transaction Risk Scoring)

### What to say
"This is where Redis really shines in banking. Redis serves as the online feature store for real-time fraud scoring. Account features, customer features, transaction features — all stored in Redis Hashes for sub-millisecond access. A scoring microservice queries Redis and gets a risk score in under 10ms."

### Demo steps
1. Submit a normal transaction — see LOW risk score (green)
2. Submit a high-amount transaction — see MEDIUM risk
3. Submit rapid-fire transactions (burst) — velocity check triggers HIGH risk
4. Submit from unusual location — see CRITICAL risk
5. Show the transaction stream log

### Key Redis commands to highlight
- `ZADD` + `ZRANGEBYSCORE` — velocity tracking
- `XADD` / `XRANGE` — transaction stream
- `HGETALL` — feature retrieval

### Talking points
- 60x faster than traditional database for feature serving
- Active-Active for multi-geo with 99.999% SLA
- Sorted Sets for time-windowed velocity checks
- Streams for audit trail and model retraining

---

## UC7: Feature Store

### What to say
"Redis isn't just a cache — it's a real-time data platform. Here we use it as a feature store, ingesting time series data from multiple sources and serving it via the Redis Query Engine. Sub-millisecond response times, no matter where your users are."

### Demo steps
1. Show pre-loaded client features (3 different risk profiles)
2. Select a client — view their feature vector
3. Update a feature in real-time (e.g., increment transaction count)
4. Recalculate risk score — see it change based on updated features

### Key Redis commands to highlight
- `HSET` / `HGETALL` — feature storage
- `HINCRBY` — real-time feature updates
- `FT.SEARCH` — query features via RQE

### Talking points
- Online feature store for ML model serving
- Real-time feature updates (not batch)
- Redis Query Engine for complex queries on features

---

## UC8: Document Database

### What to say
"Redis 8 includes a full document database with JSON support and a powerful query engine. You can store regulation documents, search them with full-text queries, and even do vector similarity search — all from Redis, with sub-10ms response times."

### Demo steps
1. Type a full-text search query (e.g., "customer authentication")
2. Show results with relevance scores
3. Switch to vector search mode — find semantically similar documents
4. Try hybrid search — combine text filter + vector similarity

### Key Redis commands to highlight
- `JSON.SET` — store documents
- `FT.CREATE` — index with TEXT + VECTOR fields
- `FT.SEARCH` — full-text query
- `FT.SEARCH ... KNN` — vector similarity

### Talking points
- No need for Elasticsearch + separate vector DB
- One database for text search + vector search + structured queries
- Redis Query Engine supports full-text, numeric, tag, and vector fields
- <10ms for document retrieval

---

## UC9: AI Agent Memory + RAG

### What to say
"AI agents need memory — both short-term (current conversation) and long-term (past interactions). Redis provides both, plus RAG retrieval for knowledge base search. This is the architecture behind every serious AI banking assistant."

### Demo steps
1. Start a conversation — ask about wire transfers
2. Show the Memory Inspection panel — see short-term memory (Hash with TTL)
3. Ask a follow-up — agent recalls context from short-term memory
4. Ask about a topic from past interactions — agent retrieves from long-term memory (vector search)
5. Ask a regulatory question — agent pulls from knowledge base (RAG)
6. Show which memories and KB docs were retrieved

### Key Redis commands to highlight
- `HSET` + `EXPIRE` — short-term conversation memory
- `JSON.SET` + `FT.SEARCH KNN` — long-term memory retrieval
- `FT.SEARCH KNN` — RAG document retrieval

### Talking points
- Short-term memory: Redis Hash with TTL = automatic cleanup
- Long-term memory: vector search over past interactions
- RAG: same Redis instance serves as vector store
- No external vector database needed — Redis does it all

---

## UC10: Cache-Aside Pattern

### What to say
"This is what most people think of when they hear Redis — caching. But the cache-aside pattern is more than just GET/SET. You check Redis first, on a miss you fetch from the database and store the result with a TTL. Second request? Sub-millisecond. The key is the TTL — it controls freshness and auto-cleanup."

### Demo steps
1. Select a banking product (e.g., "Fixed Rate Mortgage")
2. Click "Fetch Product" — first call is a CACHE MISS (~200ms)
3. Click "Fetch Product" again — now it's a CACHE HIT (<1ms)
4. Show the latency difference in the stats panel
5. Click "Clear Cache" — evict the product
6. Fetch again — back to MISS, then HIT on second fetch
7. Show the hit ratio climbing as you make more requests

### Key Redis commands to highlight
- `GET workshop:cache:product:{id}` — check cache
- `SET workshop:cache:product:{id} ... EX 300` — store with TTL
- `DEL` — cache eviction on data change

### Talking points
- 200x speedup: 200ms → <1ms on cache hit
- TTL = automatic freshness control, no stale data forever
- Cache eviction on write for data consistency
- Spring `@Cacheable` annotation for zero-code caching
- Hit ratio is the key metric — aim for >80% in production

---

## UC11: Real-time Transaction Monitoring

### What to say
"Banks need to monitor transactions in real time — TPS, amounts, risk scores. Redis Streams are perfect for this. XADD ingests transaction events with auto-generated timestamp IDs, XRANGE queries them by time window, and the app aggregates into time buckets. You get a live dashboard with sub-millisecond writes and reads — using a core Redis data structure."

### Demo steps
1. Click "Start Simulation" — watch the chart build up (~2 TPS)
2. Point out the metrics cards: TPS, total count, average amount
3. Click "Inject Anomaly" — see the spike in the chart and the High Risk % jump
4. Click "Stop" to pause, then "Reset" to clear
5. Show the Redis CLI tab — explain XADD, XRANGE, XLEN

### Key Redis commands
- `XADD` — add a transaction event with amount and risk score fields
- `XRANGE` — query entries in a timestamp range
- `XLEN` — get total stream length
- `XTRIM` — bound the stream with MAXLEN

### Talking points
- Redis Streams: append-only log with ms-precision timestamp IDs
- XTRIM MAXLEN keeps the stream bounded (last 10,000 entries)
- Aggregation done in Java: COUNT, AVG, SUM per 1-second bucket
- Stream IDs are time-based — natural fit for time-range queries
- Perfect for real-time dashboards, alerting, and anomaly detection

---

## UC12: ATM & Branch Finder (Geospatial)

### What to say
"Location matters in banking. Customers need to find the nearest ATM or branch, filtered by services. Redis Geospatial gives you sub-millisecond radius search. Combine it with JSON + Query Engine for rich filtering — geo plus type, services, hours — all in one query."

### Demo steps
1. Start with Native Geospatial tab — click Puerta del Sol preset
2. Search with 2km radius — show ATMs and branches on the map
3. Point out the GEOSEARCH command — simple, fast, returns distance
4. Switch to JSON + Query Engine tab — same location
5. Filter by type: ATM only — show how the FT.SEARCH query adds @type:{atm}
6. Filter by service: advisor — only branches with advisor service
7. Compare: native is simpler, RQE adds rich filtering in a single query

### Key Redis commands to highlight
- `GEOADD` — add coordinates to a sorted set
- `GEOSEARCH` — radius search with distance, sorted
- `JSON.SET` — store rich documents with location field
- `FT.CREATE` with `GEO` + `TAG` fields — index for geo+filter queries
- `FT.SEARCH` with `@location:[lng lat radius km]` — geo filter in RQE

### Talking points
- Native geo: O(N+log(M)) — extremely fast for pure proximity
- RQE geo: combines geo with any other filter in a single query — no application-side filtering
- Both approaches use the same Redis instance — no external geo database needed
- Real-world: branch locators, delivery radius, fraud geo-fencing

---

## Closing

### Key takeaways
1. Redis is not just a cache — it's a real-time data platform
2. One database for sessions, rate limiting, fraud detection, feature store, document search, AND AI memory
3. Sub-millisecond latency for all operations
4. Built-in search engine (full-text + vector) since Redis 8
5. Active-Active for multi-geo banking requirements

### Questions?
Open any demo and let the audience try it themselves.