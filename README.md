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

- Java 17+
- Docker and Docker Compose v2

## Quick Start

### Development mode (hot reload)

```bash
docker compose up -d          # Start Redis only
./mvnw spring-boot:run        # Start app with hot reload
# Open http://localhost:8080
```

### Workshop mode (no Java required)

```bash
docker compose --profile workshop up -d --build   # Start Redis + App
# Open http://localhost:8080
```

## Project Structure

```
src/main/java/com/redis/workshop/
├── WorkshopApplication.java        # Spring Boot entry point
├── config/
│   └── RedisConfig.java            # Lettuce connection pool
├── controller/
│   ├── HomeController.java         # Landing page
│   └── UseCaseController.java      # Use case routing
└── service/                        # Business logic (per use case)

src/main/resources/
├── application.yml                 # Redis + Thymeleaf config
├── templates/
│   ├── layout.html                 # Shared layout with nav
│   ├── index.html                  # Landing page (13 cards)
│   └── usecase-{1..13}.html        # Use case pages
└── static/
    ├── css/redis-brand.css         # Redis brand design tokens
    ├── img/icons/{light,dark}/   # Redis brand icons per theme
    ├── js/main.js                  # Dark mode toggle + utils
    ├── js/usecase-{1..13}.js       # Per-use-case JS
    └── vendor/prism/               # Syntax highlighting
```

## Stack

- **Backend**: Spring Boot 3.4.x, Spring Data Redis (Lettuce), Java 17
- **Frontend**: Thymeleaf + Vanilla JS, Redis brand CSS, Prism.js
- **Database**: Redis 8 (RQE, JSON, Search, vector support)

## UI Features

- **Redis brand icons** — Each use case card uses official Redis PNG icons with automatic light/dark theme switching
- **Dark/light mode** — Clean SVG toggle (sun/moon) with system preference detection
- **No emojis** — All UI elements use text labels and SVG icons for a professional look
- **Code showcase** — Each use case includes curated Java + Redis CLI snippets with Prism.js syntax highlighting
- **Responsive layout** — Two-panel grid (demo + code) that adapts to mobile

## Redis Connection

Default config connects to `localhost:6379`. Override with Spring Boot environment variables:

```sh
SPRING_DATA_REDIS_HOST=myhost SPRING_DATA_REDIS_PORT=6380 ./mvnw spring-boot:run
```
