# AGENTS.md — Project Drive

You are implementing Project Drive, an Android-first social navigation and driving telemetry application. **Read `docs/SPEC.md` in full before coding.** The specification is authoritative for phase scope, architecture, security, and acceptance gates.

## Fixed choices
- Native Android: Kotlin, Jetpack Compose, Coroutines/Flow, Hilt, Room.
- Backend: Go modular monolith, REST + WebSockets, cloud-neutral OCI container.
- Data: PostgreSQL + PostGIS. In-memory active state is allowed only in the single-instance Phase 0 prototype; design Redis-ready interfaces.
- Navigation: OpenStreetMap data, an open Android renderer (MapLibre candidate), and self-hosted open routing (Valhalla candidate); verify Kenya coverage and licensing before final selection, behind NavigationEngine. No required proprietary or metered map service.
- Telemetry: phone sensors and GPS first; OBD-II is future scope.

## Execution rules
1. Implement only Phase 0 milestones M0–M6, sequentially, unless explicitly instructed otherwise.
2. Before changes, inspect repository and propose next milestone tasks.
3. Keep builds passing; include tests, migrations, docs, and runnable instructions.
4. Enforce authenticated trip membership on every API and WebSocket operation. Location sharing must be explicit and revocable.
5. Do not invent SDK APIs, dependency versions, pricing, or test outcomes. Flag missing credentials or hardware tests.
6. Never commit secrets or log raw GPS data.
7. After each milestone, report files changed, commands run, tests passed/failed, blockers, and next steps.

## First task
Read docs/SPEC.md, then implement M0 only: repository layout, Go API health/readiness, PostGIS Compose, migrations, Android Compose skeleton, tests, CI, README and .env.example.
