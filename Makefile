.DEFAULT_GOAL := help

GREEN := \033[0;32m
RED := \033[0;31m
YELLOW := \033[0;33m
RESET := \033[0m

.PHONY: help up dev flush down clean

help: ## Show all available workshop commands
	@echo "\033[1;36m>>> help\033[0m"
	@echo "Available targets:"
	@awk 'BEGIN {FS = ":.*## "}; /^[a-zA-Z0-9_.-]+:.*## / {printf "  \033[0;32m%-12s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

up: ## Start Docker services and wait for Redis to respond to PING
	@echo "\033[1;36m>>> up\033[0m"
	@docker compose up -d
	@printf "$(YELLOW)Waiting for Redis to become healthy...$(RESET)\n"
	@until docker exec workshop-redis redis-cli ping >/dev/null 2>&1; do sleep 1; done
	@printf "$(GREEN)Redis is healthy.$(RESET)\n"

dev: ## Run the app — auto-detect empty Redis and load data when needed
	@echo "\033[1;36m>>> dev\033[0m"
	@exec ./mvnw spring-boot:run -Dspring-boot.run.fork=false

flush: ## Wipe Redis data without touching Docker
	@echo "\033[1;36m>>> flush\033[0m"
	@printf "$(YELLOW)Flushing Redis data without touching Docker...$(RESET)\n"
	@if command -v redis-cli >/dev/null 2>&1; then \
		redis-cli -p 6379 FLUSHALL; \
	else \
		printf "$(YELLOW)Host redis-cli not found. Using container redis-cli instead...$(RESET)\n"; \
		docker exec workshop-redis redis-cli -p 6379 FLUSHALL; \
	fi
	@printf "$(GREEN)Redis data flushed.$(RESET)\n"

down: ## Stop workshop Docker services
	@echo "\033[1;36m>>> down\033[0m"
	@docker compose down

clean: ## Clean Docker containers/volumes and Java build outputs
	@echo "\033[1;36m>>> clean\033[0m"
	@printf "$(YELLOW)→ Stopping Docker services and removing volumes...$(RESET)\n"
	@docker compose down -v
	@printf "$(YELLOW)→ Removing Maven build outputs...$(RESET)\n"
	@./mvnw clean
	@printf "$(GREEN)Cleanup complete.$(RESET)\n"