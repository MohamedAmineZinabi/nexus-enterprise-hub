# NexusEnterprise Hub

Enterprise-grade, production-ready Full-Stack & Multi-Agent AI Platform.

---

## Architecture Overview

- **Frontend**: Angular 18+ (TypeScript, Standalone Components, Signals, SCSS Design System)
- **Core Backend**: Java Spring Boot 3.x (Auth, Users, Tenancy, Kafka Producer, gRPC Client)
- **AI & ML Backend**: Python FastAPI (Multi-Agent Systems with LangGraph, Advanced Hybrid RAG with Qdrant, Model Context Protocol (MCP), and Kafka Consumer)
- **Inter-service Communication**: gRPC & Protocol Buffers (Binary, Low-Latency)
- **Event Streaming**: Apache Kafka (KRaft mode, zero-Zookeeper)
- **State & Caching**: Redis 7 (Distributed locking, agent state caching, rate limiting)
- **Databases**: PostgreSQL 16 (Relational DB) + Qdrant (Vector DB for AI embeddings)
- **Infrastructure**: Docker & Docker Compose

---

## Quick Start (Base Infrastructure)

### 1. Prerequisites
- Docker & Docker Compose v2+
- Git

### 2. Launch Infrastructure
```bash
# 1. Clone & enter project
cd nexus-enterprise-hub

# 2. Setup environment variables
cp .env.example .env

# 3. Start PostgreSQL, Redis, Qdrant, and Kafka (KRaft)
docker compose --env-file .env -f deploy/docker-compose.yml up -d
```

### 3. Verify Health
- **PostgreSQL**: `localhost:5432` (`core` schema initialized)
- **Redis**: `localhost:6379`
- **Qdrant**: `http://localhost:6333/dashboard`
- **Kafka**: `localhost:9092`
  - Topics provisioned: `agent.task.requested`, `agent.task.events`, `agent.task.completed`, `agent.task.dlq`
