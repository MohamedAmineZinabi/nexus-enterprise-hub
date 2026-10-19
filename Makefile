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
