# Convenience wrapper around Colima + Docker Compose + Maven.
# Run `make` or `make help` to see everything.

SHELL := /bin/bash
COMPOSE := docker compose

# Colima exposes its daemon on a non-standard socket. Testcontainers needs BOTH:
#   DOCKER_HOST                          -> how the JVM talks to the daemon
#   TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE -> the path Ryuk bind-mounts *inside* the VM
export DOCKER_HOST := unix://$(HOME)/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE := /var/run/docker.sock

.DEFAULT_GOAL := help

## ---------- Colima ----------

.PHONY: colima-up
colima-up: ## Start the Colima VM (4 CPU / 8 GB) if it is not already running
	@colima status >/dev/null 2>&1 || colima start --cpu 4 --memory 8 --disk 60
	@colima status

.PHONY: colima-down
colima-down: ## Stop the Colima VM (frees all RAM)
	colima stop

.PHONY: colima-nuke
colima-nuke: ## Delete the Colima VM entirely (images, volumes, everything)
	colima delete --force

## ---------- Backing services ----------

.PHONY: db cache mq infra
db: colima-up ## Start Postgres + Adminer (http://localhost:8090)
	$(COMPOSE) --profile db up -d
	@$(MAKE) --no-print-directory wait-db

cache: colima-up ## Start Redis
	$(COMPOSE) --profile cache up -d

mq: colima-up ## Start Kafka + Kafka UI (http://localhost:8091)
	$(COMPOSE) --profile mq up -d

infra: colima-up ## Start every backing service
	$(COMPOSE) --profile all up -d

.PHONY: wait-db
wait-db:
	@echo "waiting for postgres..."
	@until docker exec sb-postgres pg_isready -U app -d appdb >/dev/null 2>&1; do sleep 1; done
	@echo "postgres ready on localhost:55432 (app/app, db=appdb)"

.PHONY: ps logs down clean
ps: ## Show running containers
	$(COMPOSE) --profile all ps

logs: ## Tail logs of all running services (Ctrl-C to exit)
	$(COMPOSE) --profile all logs -f --tail=50

down: ## Stop and remove containers (volumes survive)
	$(COMPOSE) --profile all down

clean: ## Stop containers AND delete volumes (fresh database next time)
	$(COMPOSE) --profile all down -v

.PHONY: psql redis-cli
psql: ## Open a psql shell against the running Postgres
	docker exec -it sb-postgres psql -U app -d appdb

redis-cli: ## Open a redis-cli shell against the running Redis
	docker exec -it sb-redis redis-cli

## ---------- Build & test ----------

.PHONY: build test verify
build: ## Compile every module, skip tests
	./mvnw -q -DskipTests package

test: ## Run every test (Testcontainers modules need Colima up)
	./mvnw test

verify: colima-up ## Full build + all tests including integration
	./mvnw verify

## ---------- Run a module ----------
# Each app binds its own port so you can run several at once.

.PHONY: run-01 run-02 run-03 run-04 run-05
run-01: ## Run module 01 core-web        -> http://localhost:8081
	./mvnw -pl 01-core-web spring-boot:run

run-02: db ## Run module 02 data-jpa     -> http://localhost:8082
	./mvnw -pl 02-data-jpa spring-boot:run

run-03: db ## Run module 03 security     -> http://localhost:8083
	./mvnw -pl 03-security spring-boot:run

run-04: cache ## Run module 04 resilience -> http://localhost:8084
	./mvnw -pl 04-resilience spring-boot:run

run-05: mq ## Run module 05 messaging     -> http://localhost:8085
	./mvnw -pl 05-messaging spring-boot:run

.PHONY: help
help:
	@echo ""
	@echo "  Spring Boot interview prep — available targets"
	@echo ""
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'
	@echo ""
