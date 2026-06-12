.DEFAULT_GOAL := help

OPENAI_API_KEY ?=
export OPENAI_API_KEY

GREEN := \033[0;32m
RED := \033[0;31m
YELLOW := \033[0;33m
RESET := \033[0m

.PHONY: help up down logs flush seed embeddings dev compile test clean demo fresh status redis-cli

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

logs: ## Follow Docker Compose logs
	@echo "\033[1;36m>>> logs\033[0m"
	@docker compose logs -f

flush: ## Flush all Redis data after an interactive confirmation prompt
	@echo "\033[1;36m>>> flush\033[0m"
	@printf "$(YELLOW)This will run FLUSHALL on workshop-redis. Continue? [y/N] $(RESET)"; \
	read -r confirm; \
	case "$$confirm" in \
		y|Y|yes|YES) \
			docker exec workshop-redis redis-cli FLUSHALL; \
			printf "$(GREEN)Redis data flushed.$(RESET)\n" ;; \
		*) \
			printf "$(RED)Flush cancelled.$(RESET)\n"; \
			exit 1 ;; \
	esac

seed: ## Force-reload workshop data, wait for startup completion, then stop the app
	@echo "\033[1;36m>>> seed\033[0m"
	@log_file="$$(mktemp -t workshop-seed.XXXXXX.log)"; \
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
		if grep -m 1 "Started WorkshopApplication" "$$log_file" >/dev/null 2>&1; then \
			printf "$(GREEN)Workshop application started; stopping seed run.$(RESET)\n"; \
			cleanup; \
			trap - EXIT INT TERM; \
			exit 0; \
		fi; \
		sleep 2; \
	done; \
	if grep -m 1 "Started WorkshopApplication" "$$log_file" >/dev/null 2>&1; then \
		printf "$(GREEN)Workshop application completed startup.$(RESET)\n"; \
		trap - EXIT INT TERM; \
		rm -f "$$log_file"; \
	else \
		printf "$(RED)Seed run failed before startup completed. Last log lines:$(RESET)\n"; \
		tail -n 40 "$$log_file"; \
		exit 1; \
	fi

embeddings: ## Flush Redis, delete cached embedding JSONs, and regenerate them
	@echo "\033[1;36m>>> embeddings\033[0m"
	@if [ -z "$(OPENAI_API_KEY)" ]; then \
		printf "$(RED)OPENAI_API_KEY is not set. Export it or pass OPENAI_API_KEY=... to make embeddings.$(RESET)\n"; \
		exit 1; \
	fi
	@$(MAKE) flush
	@printf "$(YELLOW)Removing cached embedding JSON files...$(RESET)\n"
	@rm -f src/main/resources/data/kb-embeddings.json \
		src/main/resources/data/uc9-kb-embeddings.json \
		src/main/resources/data/uc9-memory-embeddings.json
	@printf "$(GREEN)Cached embedding JSON files removed.$(RESET)\n"
	@$(MAKE) seed

dev: ## Run the Spring Boot app in the foreground
	@echo "\033[1;36m>>> dev\033[0m"
	@exec ./mvnw spring-boot:run -Dspring-boot.run.fork=false

compile: ## Compile the Spring Boot project
	@echo "\033[1;36m>>> compile\033[0m"
	@./mvnw compile

test: ## Run the test suite
	@echo "\033[1;36m>>> test\033[0m"
	@./mvnw test

clean: ## Clean build outputs
	@echo "\033[1;36m>>> clean\033[0m"
	@./mvnw clean

demo: ## Start Docker services, then run the app in the foreground
	@echo "\033[1;36m>>> demo\033[0m"
	@$(MAKE) up
	@$(MAKE) dev

fresh: ## Start Docker, flush Redis, reseed data, then run the app
	@echo "\033[1;36m>>> fresh\033[0m"
	@$(MAKE) up
	@$(MAKE) flush
	@$(MAKE) seed
	@$(MAKE) dev

status: ## Show Redis keyspace info plus AMS and app health endpoints
	@echo "\033[1;36m>>> status\033[0m"
	@printf "$(YELLOW)Redis keyspace$(RESET)\n"
	@docker exec workshop-redis redis-cli INFO keyspace 2>/dev/null | head -5 || printf "$(RED)Redis is unavailable.$(RESET)\n"
	@printf "\n$(YELLOW)AMS health$(RESET)\n"
	@curl -fsS http://localhost:8000/v1/health || printf "$(RED)AMS is unavailable at http://localhost:8000/v1/health$(RESET)\n"
	@printf "\n$(YELLOW)App health$(RESET)\n"
	@curl -fsS http://localhost:8080/api/health || printf "$(RED)App is unavailable at http://localhost:8080/api/health$(RESET)\n"
	@printf "\n"

redis-cli: ## Open an interactive redis-cli shell inside the Redis container
	@echo "\033[1;36m>>> redis-cli\033[0m"
	@docker exec -it workshop-redis redis-cli