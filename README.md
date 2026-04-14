# Redis Banking Workshop Demo

Spring Boot 3.x workshop application showcasing **7 Redis use cases** for banking.
Each use case includes a **live interactive demo** and a **code showcase panel** with curated Redis snippets.

## Use Cases

| # | Demo | Key Redis Features |
|---|------|--------------------|
| 1 | Session Management + Auth Token | Hash, JSON, TTL |
| 2 | Rate Limiting (Open Banking / PSD2) | String INCR, EXPIRE |
| 3 | Transaction Deduplication | Set/Bloom Filter, Hash + TTL |
| 4 | Real-time Fraud Detection | Sorted Set, Streams, RQE |
| 5 | Feature Store (Risk Scoring) | Hash, TTL, RQE |
| 6 | Document Search (Full-text + Vector) | Vector, RQE, JSON |
| 7 | AI Banking Assistant (Memory + RAG) | Hash, Vector, Streams, JSON, TTL |

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
├── service/                        # Business logic (per use case)
└── model/                          # Domain models

src/main/resources/
├── application.yml                 # Redis + Thymeleaf config
├── templates/
│   ├── layout.html                 # Shared layout with nav
│   ├── index.html                  # Landing page (7 cards)
│   └── usecase-{1..7}.html         # Use case pages
└── static/
    ├── css/redis-brand.css         # Redis brand design tokens
    ├── js/main.js                  # Dark mode toggle + utils
    ├── js/usecase-{1..7}.js        # Per-use-case JS
    └── vendor/prism/               # Syntax highlighting
```

## Stack

- **Backend**: Spring Boot 3.4.x, Spring Data Redis (Lettuce), Java 17
- **Frontend**: Thymeleaf + Vanilla JS, Redis brand CSS, Prism.js
- **Database**: Redis Stack 7.4.2 (RQE, RedisJSON, RedisSearch, vector support)

## Redis Connection

Default config connects to `localhost:6379`. Override with Spring Boot environment variables:

```sh
SPRING_DATA_REDIS_HOST=myhost SPRING_DATA_REDIS_PORT=6380 ./mvnw spring-boot:run
```
