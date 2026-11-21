ENV_FILE := .env
# Base stack + local-development overrides (backend/infra ports bound to 127.0.0.1 only).
# For a production-shaped run without published backend ports use: COMPOSE_FILES="-f deploy/docker-compose.yml"
COMPOSE_FILES ?= -f deploy/docker-compose.yml -f deploy/docker-compose.override.yml
COMPOSE := docker compose --env-file $(ENV_FILE) $(COMPOSE_FILES)

.DEFAULT_GOAL := status

.PHONY: check-env
check-env:
	@if [ ! -f $(ENV_FILE) ]; then \
		echo "Missing $(ENV_FILE). Copy .env.example to $(ENV_FILE) and replace every 'change-me' secret."; \
		exit 1; \
	fi
	@if grep -q "change-me" $(ENV_FILE); then \
		echo "$(ENV_FILE) still contains 'change-me' placeholder secrets. Replace them before starting."; \
		exit 1; \
	fi

.PHONY: proto
proto:
	@echo "Compiling Protocol Buffer contracts..."
	@bash scripts/compile-proto.sh

.PHONY: build
build: check-env
	@echo "Building all NexusEnterprise Hub production images..."
	$(COMPOSE) build

.PHONY: up
up: check-env
	@echo "Starting full NexusEnterprise Hub stack (8 services)..."
	$(COMPOSE) up -d --build

.PHONY: down
down:
	@echo "Stopping NexusEnterprise Hub stack..."
	$(COMPOSE) down

.PHONY: logs
logs:
	$(COMPOSE) logs -f

.PHONY: clean
clean:
	@echo "WARNING: Tearing down stack and destroying all persistent volumes..."
	$(COMPOSE) down -v

.PHONY: status
status:
	@echo "=== NexusEnterprise Hub Stack Status ==="
	$(COMPOSE) ps

.PHONY: core-test
core-test:
	@echo "Running Core Service test suite (Maven)..."
	docker run --rm -v "${PWD}/services/core-service:/workspace/services/core-service" -v "${PWD}/proto:/workspace/proto" -v nexus-m2-cache:/root/.m2 -w /workspace/services/core-service maven:3.9.9-eclipse-temurin-21 mvn -B test

.PHONY: ai-dev-image
ai-dev-image:
	@echo "Building AI Service dev/test image (runtime + test & eval tooling)..."
	docker build -f services/ai-service/Dockerfile --target dev -t nexus-hub/ai-service:dev .

.PHONY: ai-test
ai-test: ai-dev-image
	@echo "Running AI Service unit, security, MCP & DLQ tests (pytest)..."
	docker run --rm -v "${PWD}/services/ai-service:/app" -w /app nexus-hub/ai-service:dev -q --ignore=tests/test_multi_agent_kafka.py --ignore=tests/evals

.PHONY: ai-eval
ai-eval: ai-dev-image
	@echo "Running AI Evaluation & Red-Teaming Suite (RAGAS, DeepEval, Giskard)..."
	docker run --rm -v "${PWD}/services/ai-service:/app" -w /app -e OPENAI_API_KEY="${OPENAI_API_KEY}" nexus-hub/ai-service:dev -q tests/evals/

.PHONY: frontend-build
frontend-build:
	@echo "Verifying Frontend production build..."
	docker run --rm -v "${PWD}/services/frontend:/app" -w /app node:20-alpine sh -c "npm ci && npm run build"

.PHONY: test-all
test-all: core-test ai-test ai-eval frontend-build
	@echo "All NexusEnterprise Hub suites passed!"
