# PAS - Business Document Management System
# Usage: make <target>   (Windows: Git Bash / mingw32-make, Linux/macOS: make)
# Requires: docker compose v2, openssl (Git for Windows provides it), JDK 25 for `make test`

# Use Git Bash on Windows, /bin/bash on Linux/macOS/WSL
ifeq ($(OS),Windows_NT)
  SHELL := C:/PROGRA~1/Git/bin/bash.exe
  PYTHON := python
else
  SHELL := /bin/bash
  PYTHON := python3
endif
# Ensure bash can find Unix tools (Git usr/bin) even when called from cmd/PowerShell
ifeq ($(OS),Windows_NT)
  export PATH := C:/PROGRA~1/Git/usr/bin;C:/PROGRA~1/Git/bin;$(PATH)
endif
COMPOSE := docker compose
# Cross-platform Gradle wrapper: use Unix script via sh (works on macOS/Linux and Windows Git Bash).
# On pure Windows cmd without bash, fallback is gradlew.bat (see test-win).
GRADLE := sh ./gradlew

# ---------------------------------------------------------------------------
# help
# ---------------------------------------------------------------------------
.PHONY: help
help: ## Show this help
	@echo "PAS Makefile - available targets:"
	@echo "  keys / generate-keys / generate-ssh / gen-keys  Generate JWT RS256 keypair + patch Traefik jwt.yml"
	@echo "  up                                              Build (if needed) and start stack (docker compose up --build -d)"
	@echo "  build                                           Build image (uses cache)"
	@echo "  rebuild                                         Force rebuild --no-cache"
	@echo "  ps                                              Show containers"
	@echo "  logs / logs-identity / logs-workflow / logs-contract  Tail logs"
	@echo "  down                                            Stop stack, keep volumes/cache"
	@echo "  down-v / clean / nuke                           Stop stack + WIPE volumes (DB reset)"
	@echo "  test / test-unit                                Run unit tests (excludes integration)"
	@echo "  test-integration                                Run integration tests (Testcontainers)"
	@echo "  restart                                         Restart stack (down + up)"
	@echo ""
	@echo "Run from Git Bash for full Unix tools, or 'bash -lc \"make <target>\"' from PowerShell."

# ---------------------------------------------------------------------------
# keys - generate RS256 JWT keypair (private never committed)
# "generate-ssh" is an alias because users often say SSH; actually JWT RSA.
# ---------------------------------------------------------------------------
.PHONY: keys generate-keys generate-ssh gen-keys
keys: ## Generate JWT RS256 keypair (infra/keys/jwt-private.pem + jwt-public.pem) and patch Traefik jwt.yml
	@echo ">> Generating JWT keypair..."
	@mkdir -p infra/keys infra/docker/traefik/dynamic
	@openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out infra/keys/jwt-private.pem
	@openssl pkey -in infra/keys/jwt-private.pem -pubout -out infra/keys/jwt-public.pem
	@echo ">> Patching infra/docker/traefik/dynamic/jwt.yml ..."
	@$(PYTHON) -c "import pathlib; pub=pathlib.Path('infra/keys/jwt-public.pem').read_text().strip(); indented='\n'.join('            '+l for l in pub.splitlines()); pathlib.Path('infra/docker/traefik/dynamic/jwt.yml').write_text(f'''http:\n  middlewares:\n    jwt-auth:\n      plugin:\n        jwt:\n          secret: |\n{indented}\n          require:\n            iss: pas-identity\n          headerMap:\n            X-User-Id: sub\n            X-Username: username\n            X-Full-Name: full_name\n            X-Department: department\n            X-Roles: roles\n          removeMissingHeaders: true\n'''); print('   patched jwt.yml')"
	@echo ">> Done: infra/keys/jwt-private.pem (gitignored) + infra/keys/jwt-public.pem"

generate-keys: keys
generate-ssh: keys ## Alias for keys (JWT RSA, not SSH)
gen-keys: keys

.PHONY: keys-check
keys-check: ## Fail if private key missing (used by up)
	@test -f infra/keys/jwt-private.pem || (echo "ERROR: infra/keys/jwt-private.pem missing. Run 'make keys' first." && exit 1)

# ---------------------------------------------------------------------------
# docker compose
# ---------------------------------------------------------------------------
.PHONY: up build rebuild ps logs logs-identity logs-workflow logs-contract down down-v clean nuke

up: keys-check ## Build (if needed) and start stack detached (docker compose up --build -d)
	$(COMPOSE) up --build -d
	@echo ">> Stack up: http://localhost:18080 (gateway)  http://localhost:18090 (traefik dashboard)"

build: keys-check ## Build every service image (uses Docker layer cache)
	$(COMPOSE) build

rebuild: keys-check ## Force rebuild ignoring cache (slow)
	$(COMPOSE) build --no-cache

ps: ## Show compose containers
	$(COMPOSE) ps

logs: ## Tail all logs
	$(COMPOSE) logs -f

logs-identity: ## Tail identity-service logs
	$(COMPOSE) logs -f identity-service

logs-workflow: ## Tail workflow-service logs
	$(COMPOSE) logs -f workflow-service

logs-contract: ## Tail contract-service logs
	$(COMPOSE) logs -f contract-service

down: ## Stop and remove containers/network (KEEP pgdata volume & image cache)
	$(COMPOSE) down

down-v: ## Stop and remove containers/network + VOLUMES (wipes DB) 
	$(COMPOSE) down -v

clean: down-v ## Alias for down-v
nuke: down-v  ## Alias for down-v

# ---------------------------------------------------------------------------
# tests - unit tests only (integration Testcontainers excluded by default)
# ---------------------------------------------------------------------------
.PHONY: test test-unit test-integration test-win

test: ## Run all unit tests (excludes integration tag) - cross-platform via sh
	$(GRADLE) test --continue

test-unit: test ## Alias

test-integration: ## Run integration tests (needs Docker, runs Testcontainers)
	$(GRADLE) test --tests "*IT" -Dtest.single=*IT -PincludeIntegration || $(GRADLE) test --continue -DincludeIntegration=true

test-win: ## Windows cmd fallback (no bash) - uses gradlew.bat directly
	gradlew.bat test --continue

# ---------------------------------------------------------------------------
# dev helpers
# ---------------------------------------------------------------------------
.PHONY: restart
restart: down up ## Restart stack (down + up, keeps volumes/cache)
