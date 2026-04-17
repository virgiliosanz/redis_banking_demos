# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy POM and download dependencies first (cache layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# Default: connect to Redis container by name
ENV SPRING_DATA_REDIS_HOST=redis

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
