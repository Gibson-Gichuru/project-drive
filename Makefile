SHELL := /bin/sh
JAVA_HOME ?= $(if $(wildcard /opt/android-studio/jbr/bin/java),/opt/android-studio/jbr)
ANDROID_HOME ?= $(if $(ANDROID_SDK_ROOT),$(ANDROID_SDK_ROOT),$(HOME)/Android/Sdk)
export JAVA_HOME ANDROID_HOME
COMPOSE := docker compose -f infra/compose.yaml --env-file .env

.PHONY: setup doctor ci test api-test integration security android-build android-test android-device-build android-lint compose-config homelab-config up down logs migrate clean

setup:
	@test -f .env || cp .env.example .env

doctor:
	@go version
	@docker compose version
	@test -x "$${JAVA_HOME:-}/bin/java" || test -x /opt/android-studio/jbr/bin/java || { echo 'Set JAVA_HOME to Android Studio bundled JBR or install JDK 17+'; exit 1; }
	@test -d "$${ANDROID_HOME:-$${ANDROID_SDK_ROOT:-$$HOME/Android/Sdk}}" || { echo 'Set ANDROID_HOME to the Android SDK path'; exit 1; }

ci: test integration security compose-config homelab-config android-build android-test android-device-build android-lint

test: api-test

api-test:
	cd services/api && go vet ./... && go test -race ./...

integration: setup
	$(COMPOSE) up -d --build
	$(COMPOSE) run --rm migrate
	$(COMPOSE) run --rm migrate
	$(COMPOSE) exec -T db sh -ec 'test "$$(psql -U "$$POSTGRES_USER" -d "$$POSTGRES_DB" -Atc "SELECT count(*) FROM schema_migrations WHERE version IN ('\''0001_initial'\'', '\''0002_trip_domain'\'', '\''0003_member_removal'\'')")" = "3"'
	$(COMPOSE) run --rm --build trip-tests
	curl --fail --silent http://localhost:8080/healthz >/dev/null
	curl --fail --silent http://localhost:8080/readyz >/dev/null

security:
	cd services/api && go run golang.org/x/vuln/cmd/govulncheck@v1.9.0 ./...

android-build:
	cd apps/android && ./gradlew --no-daemon :app:assembleDebug

android-test:
	cd apps/android && ./gradlew --no-daemon :app:testDebugUnitTest

android-device-build:
	cd apps/android && ./gradlew --no-daemon :app:assembleDebugAndroidTest

android-lint:
	cd apps/android && ./gradlew --no-daemon :app:lintDebug

compose-config: setup
	$(COMPOSE) config --quiet
	MAP_DATA_DIR=/tmp/project-drive-map-data docker compose -f infra/compose.yaml -f infra/maps.compose.yaml --env-file .env config --quiet

homelab-config:
	kubectl kustomize deploy/homelab >/dev/null

up: setup
	$(COMPOSE) up --build -d

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f --tail=100

migrate: setup
	$(COMPOSE) run --rm migrate

clean:
	$(COMPOSE) down -v --remove-orphans
