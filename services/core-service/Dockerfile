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

# --- Stage 2: Minimal Production Runtime (Alpine) ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Fixed UID/GID 1000 shared with ai-service so both containers can read/write the /shared-uploads volume
RUN addgroup -S -g 1000 nexus && adduser -S -u 1000 -G nexus nexus

# Copy built JAR from builder
COPY --from=builder /workspace/services/core-service/target/core-service-*.jar /app/app.jar

# Prepare shared uploads mount point owned by the non-root runtime user (no world-writable dirs)
RUN mkdir -p /shared-uploads && chown -R 1000:1000 /app /shared-uploads
USER 1000:1000

# 8080 = API, 8081 = actuator (management.server.port; not exposed through NGINX)
EXPOSE 8080 8081

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=45s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
