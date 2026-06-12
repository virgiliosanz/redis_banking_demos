.DEFAULT_GOAL := help

OPENAI_API_KEY ?=
export OPENAI_API_KEY

GREEN := \033[0;32m
RED := \033[0;31m
YELLOW := \033[0;33m
RESET := \033[0m

DATA_DIR := src/main/resources/data
EMBEDDING_FILES := $(DATA_DIR)/kb-embeddings.json $(DATA_DIR)/uc9-kb-embeddings.json $(DATA_DIR)/uc9-memory-embeddings.json

.PHONY: help up down clean seed dev embeddings

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

seed: ## Flush Redis and reload all data from pre-computed JSON embeddings
	@echo "\033[1;36m>>> seed\033[0m"
	@printf "$(YELLOW)Flushing Redis data without prompt...$(RESET)\n"
	@docker exec workshop-redis redis-cli FLUSHALL >/dev/null
	@printf "$(GREEN)Redis data flushed.$(RESET)\n"
	@log_file="$$(mktemp -t workshop-seed.XXXXXX.log)"; \
	last_line=0; \
	startup_reported=0; \
	printf "$(YELLOW)Starting application with workshop.startup.force-reload=true...$(RESET)\n"; \
	./mvnw spring-boot:run -Dspring-boot.run.fork=false -Dspring-boot.run.arguments="--workshop.startup.force-reload=true" > "$$log_file" 2>&1 & \
	pid=$$!; \
	cleanup() { \
		if kill -0 "$$pid" >/dev/null 2>&1; then \
			kill "$$pid" 2>/dev/null || true; \
			sleep 3; \
			kill -0 "$$pid" 2>/dev/null && kill -9 "$$pid" 2>/dev/null || true; \
		fi; \
		wait "$$pid" >/dev/null 2>&1 || true; \
		rm -f "$$log_file"; \
	}; \
	trap 'cleanup' EXIT INT TERM; \
	while kill -0 "$$pid" >/dev/null 2>&1; do \
		if [ -f "$$log_file" ]; then \
			total_lines="$$(wc -l < "$$log_file" | tr -d ' ')"; \
			if [ "$$total_lines" -gt "$$last_line" ]; then \
				new_lines="$$(sed -n "$$(($$last_line + 1)),$${total_lines}p" "$$log_file")"; \
				relevant_lines="$$(printf '%s\n' "$$new_lines" | grep -iE 'UC[0-9]|cleanup|Generating|Loaded|skipping|pre-computed|embedding|index|OpenAI|force reload|Data loading|Started|created' || true)"; \
				if [ -n "$$relevant_lines" ]; then \
					if [ "$$startup_reported" -eq 0 ] && printf '%s\n' "$$relevant_lines" | grep -m 1 'Started WorkshopApplication' >/dev/null 2>&1; then \
						startup_reported=1; \
					fi; \
					printf '%s\n' "$$relevant_lines" | \
						sed -E 's/^[0-9-]{10}[T ][0-9:.+-]+[[:space:]]+[A-Z]+[[:space:]]+[0-9]+[[:space:]]+---[[:space:]]+\[[^]]+\][[:space:]]+[^:]+:[[:space:]]*//' | \
						while IFS= read -r line; do \
							[ -z "$$line" ] && continue; \
							if printf '%s\n' "$$line" | grep -q 'Started WorkshopApplication'; then \
								printf "$(GREEN)✓ Started successfully$(RESET)\n"; \
							else \
								printf "$(YELLOW)→ %s$(RESET)\n" "$$line"; \
							fi; \
						done; \
				fi; \
				last_line=$$total_lines; \
			fi; \
		fi; \
		if grep -m 1 "Started WorkshopApplication" "$$log_file" >/dev/null 2>&1; then \
			if [ "$$startup_reported" -eq 0 ]; then \
				printf "$(GREEN)✓ Started successfully$(RESET)\n"; \
			fi; \
			cleanup; \
			trap - EXIT INT TERM; \
			exit 0; \
		fi; \
		sleep 1; \
	done; \
	if grep -m 1 "Started WorkshopApplication" "$$log_file" >/dev/null 2>&1; then \
		if [ "$$startup_reported" -eq 0 ]; then \
			printf "$(GREEN)✓ Started successfully$(RESET)\n"; \
		fi; \
		trap - EXIT INT TERM; \
		rm -f "$$log_file"; \
	else \
		printf "$(RED)Seed run failed before startup completed. Last log lines:$(RESET)\n"; \
		tail -n 40 "$$log_file"; \
		exit 1; \
	fi


embeddings: ## Generate OpenAI embeddings and save to JSON (requires OPENAI_API_KEY)
	@echo "\033[1;36m>>> embeddings\033[0m"
	@if [ -z "$(OPENAI_API_KEY)" ]; then \
		printf "$(RED)OPENAI_API_KEY is not set. Export it or pass OPENAI_API_KEY=... to make embeddings.$(RESET)\n"; \
		exit 1; \
	fi
	@printf "$(YELLOW)→ Deleting cached JSON files...$(RESET)\n"
	@rm -f $(EMBEDDING_FILES)
	@printf "$(YELLOW)→ Regenerating embeddings via OpenAI (this may take a few minutes)...$(RESET)\n"
	@$(MAKE) seed
	@printf "$(GREEN)Embeddings generated and saved to $(DATA_DIR)/$(RESET)\n"
	@ls -lh $(EMBEDDING_FILES)

dev: ## Run the app (no data loading — assumes Redis is already populated)
	@echo "\033[1;36m>>> dev\033[0m"
	@exec ./mvnw spring-boot:run -Dspring-boot.run.fork=false -Dspring-boot.run.arguments="--workshop.startup.load-data=false"