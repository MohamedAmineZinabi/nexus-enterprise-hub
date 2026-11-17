COMPOSE_FILE := deploy/docker-compose.yml
ENV_FILE := .env

.DEFAULT_GOAL := status

.PHONY: check-env
check-env:
	@if [ ! -f $(ENV_FILE) ]; then \
		echo "Missing $(ENV_FILE) file. Generating from .env.example..."; \
		cp .env.example $(ENV_FILE); \
	fi

.PHONY: proto
proto:
	@echo "Compiling Protocol Buffer contracts..."
	@bash scripts/compile-proto.sh

.PHONY: build
build: check-env
	@echo "Building all NexusEnterprise Hub production images..."
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) build

.PHONY: up
up: check-env
	@echo "Starting full NexusEnterprise Hub stack (8 services)..."
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) up -d --build

.PHONY: down
down:
	@echo "Stopping NexusEnterprise Hub stack..."
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) down

.PHONY: logs
logs:
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) logs -f

.PHONY: clean
clean:
	@echo "WARNING: Tearing down stack and destroying all persistent volumes..."
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) down -v

.PHONY: status
status:
	@echo "=== NexusEnterprise Hub Stack Status ==="
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) ps

.PHONY: core-test
core-test:
	@echo "Running Core Service test suite (Maven)..."
	docker run --rm -v "${PWD}/services/core-service:/workspace/services/core-service" -v "${PWD}/proto:/workspace/proto" -w /workspace/services/core-service maven:3.9.9-eclipse-temurin-21 mvn test

.PHONY: ai-test
ai-test:
	@echo "Running AI Service test suite (pytest)..."
	docker run --rm --network nexus-network -e REDIS_URL="redis://:nexus_redis_secret_token_2026@nexus-redis:6379/0" --entrypoint pytest -v "${PWD}/services/ai-service:/app" -w /app nexus-hub/ai-service:latest tests/ -v

.PHONY: frontend-build
frontend-build:
	@echo "Verifying Frontend production build..."
	docker run --rm -v "${PWD}/services/frontend:/app" -w /app node:20-alpine sh -c "npm ci && npm run build"

.PHONY: test-all
test-all: core-test ai-test frontend-build
	@echo "All NexusEnterprise Hub suites passed!"
