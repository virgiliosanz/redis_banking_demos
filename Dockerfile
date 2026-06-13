# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy POM and download dependencies first (cache layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/*.jar app.jar

# Default: connect to Redis container by name
ENV REDIS_HOST=redis
ENV SPRING_DATA_REDIS_HOST=redis

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=10 \
  CMD curl -fsS http://localhost:8080/api/health >/dev/null || exit 1

CMD ["java", "-jar", "app.jar"]
