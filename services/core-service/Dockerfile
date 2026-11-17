# ==============================================================================
# Multi-Stage Build Dockerfile for NexusEnterprise Hub - Core Service
# ==============================================================================

# --- Stage 1: Build & Compile Protobuf Contracts (glibc-based for protoc plugins) ---
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /workspace

# Copy shared proto definitions first (required by protobuf-maven-plugin)
COPY proto /workspace/proto

# Copy Maven POM and pre-fetch dependencies
WORKDIR /workspace/services/core-service
COPY services/core-service/pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source code, compile Protobuf contracts, and package Spring Boot fat JAR
COPY services/core-service/src ./src
RUN mvn clean package -DskipTests

# --- Stage 2: Minimal Production Runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S nexus && adduser -S nexus -G nexus

# Copy built JAR from builder
COPY --from=builder /workspace/services/core-service/target/core-service-*.jar /app/app.jar

# Prepare shared uploads mount point and enforce non-root ownership
RUN mkdir -p /shared-uploads && chown -R nexus:nexus /app /shared-uploads && chmod 777 /shared-uploads
USER nexus

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=45s \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
