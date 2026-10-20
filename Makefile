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

.PHONY: infra-up
infra-up: check-env
	@echo "Starting NexusEnterprise Hub base infrastructure..."
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) up -d

.PHONY: infra-down
infra-down:
	@echo "Stopping infrastructure..."
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) down

.PHONY: infra-logs
infra-logs:
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) logs -f

.PHONY: infra-clean
infra-clean:
	@echo "WARNING: Tearing down infrastructure and destroying all persistent volumes..."
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) down -v

.PHONY: status
status:
	@echo "=== NexusEnterprise Hub Infrastructure Status ==="
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) ps

.PHONY: core-build
core-build:
	@echo "Building core-service Docker image..."
	docker build -f services/core-service/Dockerfile -t nexus-hub/core-service:latest .

.PHONY: core-run
core-run:
	@echo "Running core-service on nexus-network..."
	docker run -d --name nexus-core-service --network nexus-network -p 8080:8080 --env-file $(ENV_FILE) -e POSTGRES_HOST=nexus-postgres -e REDIS_HOST=nexus-redis -e KAFKA_BOOTSTRAP_SERVERS=nexus-kafka:29092 nexus-hub/core-service:latest

.PHONY: core-test
core-test:
	@echo "Running core-service test suite..."
	docker run --rm -v "${PWD}:/workspace" -w /workspace/services/core-service maven:3.9.8-eclipse-temurin-21 mvn test
