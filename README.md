# Redis Banking Workshop Demo

Spring Boot 3.x workshop application showcasing **13 Redis use cases** for banking.
Each use case includes a **live interactive demo** and a **code showcase panel** with curated Redis snippets.

## Use Cases

| # | Demo | Key Redis Features |
|---|------|--------------------|
| 1 | Authentication Token Store | Hash, TTL, HSET, HGET |
| 2 | Session Storage | Hash, TTL, HGETALL |
| 3 | User Profile Storage | Hash, HSET, HGETALL, HINCRBY |
| 4 | Rate Limiting (Open Banking / PSD2) | String INCR, EXPIRE |
| 5 | Transaction Deduplication | SET NX EX, Hash, TTL |
| 6 | Fraud Detection (Risk Scoring) | Sorted Set, Streams, RQE |
| 7 | Feature Store | Hash, TTL, RQE |
| 8 | Document Database | JSON, RQE, Vector, Full-text |
| 9 | AI Banking Assistant (Memory + RAG + Semantic Cache) | Hash, Vector, JSON, TTL, Streams |
| 10 | Cache-Aside Pattern | String GET/SET EX, DEL |
| 11 | Real-time Transaction Monitoring | Streams, XADD, XRANGE, XLEN |
| 12 | ATM & Branch Finder (Geospatial) | Geo, JSON, RQE, GEOSEARCH |
| 13 | Distributed Locking | SET NX EX, Lua, EVAL, TTL |

## Prerequisites

- Java 17+ (for development mode)
- Docker and Docker Compose v2

## Quick Start

### Option 1: Development mode (hot reload)

Best for developing and modifying the workshop. Requires Java 17+.

```bash
# 1. Start Redis
docker compose up -d

# 2. Start the app (with hot reload)
./mvnw spring-boot:run

# 3. Open http://localhost:8080
```

### Option 2: Workshop mode (no Java required)

Best for presenting the workshop. Everything runs in Docker — no local Java needed.

```bash
# Build and start Redis + App together
docker compose --profile workshop up -d --build

# Open http://localhost:8080
```

To stop everything:

```bash
docker compose --profile workshop down
```

## Docker Setup

### Services

| Service | Image | Port | Description |
|---------|-------|------|-------------|
| `redis` | `redis:8.0-M02` | 6379 | Redis 8 with RQE, JSON, Search, Vector support |
| `app` | Built from `Dockerfile` | 8080 | Spring Boot application (workshop profile only) |
| `redis-insight` | `redis/redisinsight:latest` | 5540 | Visual Redis browser (workshop profile only) |

### Redis container

The Redis container starts automatically with a health check (`redis-cli ping`). Data is persisted in a Docker volume (`redis-data`).

```bash
# Start Redis only
docker compose up -d

# Check Redis is healthy
docker compose ps

# Connect to Redis CLI
docker exec -it redis-workshop redis-cli

# Stop Redis (data persists in volume)
docker compose down

# Stop Redis and delete data
docker compose down -v
```

### App container (workshop profile)

The app uses a multi-stage Dockerfile:
1. **Build stage**: Maven + JDK 21 — compiles the project
2. **Runtime stage**: JRE 21 only — runs the JAR (~200MB image)

The app container automatically connects to the Redis container by service name (`SPRING_DATA_REDIS_HOST=redis`).

### Redis Insight (workshop profile)

[Redis Insight](https://redis.io/insight/) provides a visual interface to inspect keys, run commands, and monitor Redis in real time.

It starts automatically with the workshop profile:

```bash
docker compose --profile workshop up -d --build
# Redis Insight available at http://localhost:5540
```

On first launch, add a connection:
- **Host**: `redis` (Docker network) or `localhost` (if accessing from host)
- **Port**: `6379`
- **Name**: Workshop

## Redis Connection

### Default (local Docker)

The app connects to `localhost:6379` by default. This works when Redis runs via Docker Compose.

### Remote Redis / Redis Cloud

Override the connection with environment variables:

```bash
# Development mode
SPRING_DATA_REDIS_HOST=your-redis-host.cloud.redislabs.com \
SPRING_DATA_REDIS_PORT=12345 \
./mvnw spring-boot:run

# Workshop mode (Docker)
SPRING_DATA_REDIS_HOST=your-redis-host SPRING_DATA_REDIS_PORT=12345 \
docker compose --profile workshop up -d --build
```

For Redis Cloud with authentication, add to `application.yml`:

```yaml
spring:
  data:
    redis:
      host: your-redis-host.cloud.redislabs.com
      port: 12345
      password: your-password
```

## LLM Integration (OpenAI)

### How it works

The workshop is designed to work **with or without** an OpenAI API key:

| Mode | UC1-UC7, UC10-UC13 | UC8 (Document DB) | UC9 (AI Assistant) |
|------|--------------------|--------------------|---------------------|
| **Without API key** | Fully functional | Full-text search works; vector search uses mock vectors (deterministic but not semantic) | Chat works with mock responses; RAG uses keyword matching; semantic cache disabled |
| **With API key** | No change | Vector search uses real embeddings (semantic similarity) | Real LLM responses via GPT-4o-mini; RAG uses real embeddings; semantic cache active with token savings |

**Bottom line**: All 13 use cases work without an API key. The OpenAI integration enhances UC8 and UC9 with real semantic search and AI-generated responses.

### Setting up OpenAI

1. Get an API key from [platform.openai.com](https://platform.openai.com/api-keys)

2. Set the environment variable:

```bash
# Development mode
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run

# Workshop mode (Docker) — pass to the app container
OPENAI_API_KEY=sk-... docker compose --profile workshop up -d --build
```

3. On startup, the app logs whether OpenAI is enabled:

```
OpenAI integration enabled (model=gpt-4o-mini, embeddingModel=text-embedding-3-small)
```

Or if no key is set:

```
OpenAI integration disabled — no API key configured. Using mock fallback.
```

### Models used

| Purpose | Model | Used by |
|---------|-------|---------|
| Chat completions | `gpt-4o-mini` | UC9 AI Assistant |
| Text embeddings | `text-embedding-3-small` (1536 dimensions) | UC8 vector search, UC9 RAG + semantic cache |

Override models in `application.yml`:

```yaml
openai:
  api-key: ${OPENAI_API_KEY:}
  model: gpt-4o-mini          # or gpt-4o, gpt-3.5-turbo
  embedding-model: text-embedding-3-small  # or text-embedding-3-large
```

### Cost estimate

The workshop uses minimal tokens. A typical demo session (10-20 questions) costs < $0.01 with gpt-4o-mini.

## Pre-computed Embeddings (UC8)

UC8 (Document Database) includes 4 regulation PDFs (PSD2, DORA, MiFID II, GDPR) that are chunked and embedded for vector search.

### Using pre-computed embeddings (default)

The file `src/main/resources/data/kb-embeddings.json` contains pre-computed chunks with mock vectors. These load automatically on startup — no API key needed.

### Generating real embeddings

For higher-quality semantic search, generate real embeddings from the PDFs:

```bash
# With OpenAI API key — generates real embeddings
OPENAI_API_KEY=sk-... ./mvnw compile exec:java \
  -Dexec.mainClass="com.redis.workshop.tools.EmbeddingGenerator" \
  -Dexec.classpathScope=compile

# Without API key — generates mock embeddings from PDF content
./mvnw compile exec:java \
  -Dexec.mainClass="com.redis.workshop.tools.MockEmbeddingGenerator" \
  -Dexec.classpathScope=compile
```

Both tools read PDFs from `src/main/resources/docs/` and write to `src/main/resources/data/kb-embeddings.json`.

## Project Structure

```
src/main/java/com/redis/workshop/
├── WorkshopApplication.java        # Spring Boot entry point
├── config/
│   ├── RedisConfig.java            # Lettuce connection pool
│   └── DocumentDataLoader.java     # PDF chunks + vector index loader
├── controller/
│   ├── HomeController.java         # Landing page
│   └── UseCaseController.java      # Use case routing
├── service/                        # Business logic (per use case)
│   ├── OpenAiService.java          # OpenAI API client (chat + embeddings)
│   ├── AssistantService.java       # UC9: AI agent memory + RAG
│   └── ...                         # One service per use case
└── tools/                          # Offline utilities
    ├── EmbeddingGenerator.java     # Generate real embeddings from PDFs
    ├── MockEmbeddingGenerator.java # Generate mock embeddings (no API key)
    └── PdfChunker.java             # PDF → text chunks

src/main/resources/
├── application.yml                 # Redis + OpenAI config
├── data/kb-embeddings.json         # Pre-computed document chunks
├── docs/*.pdf                      # Regulation PDFs (PSD2, DORA, MiFID II, GDPR)
├── templates/
│   ├── layout.html                 # Shared layout with nav
│   ├── index.html                  # Landing page (13 cards)
│   ├── guide.html                  # Workshop Presenter Guide
│   └── usecase-{1..13}.html        # Use case pages
└── static/
    ├── css/redis-brand.css         # Redis brand design tokens
    ├── img/icons/{light,dark}/     # Redis brand icons per theme
    ├── js/main.js                  # Dark mode toggle + utils
    ├── js/usecase-{1..13}.js       # Per-use-case JS
    └── vendor/prism/               # Syntax highlighting
```

## Stack

- **Backend**: Spring Boot 3.4.x, Spring Data Redis (Lettuce), Java 17+
- **Frontend**: Thymeleaf + Vanilla JS, Redis brand CSS, Prism.js
- **Database**: Redis 8 (RQE, JSON, Search, Vector support)
- **LLM** (optional): OpenAI GPT-4o-mini + text-embedding-3-small

## UI Features

- **Redis brand icons** — Each use case card uses official Redis PNG icons with automatic light/dark theme switching
- **Dark/light mode** — Clean SVG toggle (sun/moon) with system preference detection
- **No emojis** — All UI elements use text labels and SVG icons for a professional look
- **Code showcase** — Each use case includes curated Java + Redis CLI snippets with Prism.js syntax highlighting
- **Responsive layout** — Two-panel grid (demo + code) that adapts to mobile
- **Presenter guide** — Built-in guide at `/guide` with talking points and demo steps for each use case
