# Project Drive — Software Requirements Specification

**Version:** 1.1  |  **Date:** 2026-11-13  |  **Status:** Agent-ready implementation baseline

**Product:** Android-first all-in-one driving companion. **Stack:** Kotlin/Compose, Go, PostgreSQL/PostGIS, WebSockets, cloud-neutral containers.

---

## 1. Document control and implementation contract

Status: Implementation-ready baseline, version 1.1, dated 2026-11-13. Product: Project Drive. Owner: product team. Target: Android-first private alpha. This specification is the source of truth for the initial agent implementation and incorporates the open-source/no-paid-service requirement. The product must use open-source software and open data, with no required proprietary product SDK, paid API, metered service, or paid hosting dependency. Prefer self-hosted deployment on existing hardware. Open-source licensing does not eliminate hardware, electricity, connectivity, or domain costs; do not promise public hosting at zero cost. Verify current licenses, data terms, attribution, Android background-permission requirements, and service policies before integration.

MUST = required for named phase; SHOULD = preferred; MAY = optional. A feature is complete only when code, tests, documentation, and acceptance criteria pass. Never silently expand scope. Record decisions in docs/adr/.

## 2. Product vision and users

Project Drive is an all-in-one driving companion combining navigation, crowdsourced road intelligence, private live convoy tracking, phone-based driving telemetry, and personal road heatmaps. It is intended for everyday drivers, road-trip groups, car enthusiasts, and motoring communities.

Product principles: safety over competitive speed; explicit and revocable location sharing; useful solo navigation; reliable operation under intermittent connectivity; clear accuracy and freshness indicators; provider-agnostic boundaries; incremental implementation.

Primary user journeys: (A) navigate to a destination and view relevant road alerts; (B) report and verify a road hazard; (C) create a private convoy and invite friends; (D) view authorized members on the map; (E) finish a trip and inspect telemetry and history; (F) review personal heatmaps.

## 3. Scope, priorities, and release phases

Phase 0 (P0, immediate): monorepo, Android app skeleton, open mapping and navigation spike, Go API, PostgreSQL/PostGIS, development authentication, create/join/start/end private trips, two-phone WebSocket location sharing, foreground location collection, local sensor collection, rudimentary post-trip telemetry summary, tests and Docker Compose.

Phase 1 (P1): self-hostable open-source production authentication, navigation experience, incident/camera/speed-bump reports, report verification and expiry, route-aware warnings, trip persistence and usability polish.

Phase 2 (P1): robust convoy lifecycle, reconnect and stale positions, membership roles, invitations, stop-sharing, battery and background execution improvements.

Phase 3 (P1): telemetry signal processing, historical trip storage, personal heatmaps and deletion/retention controls.

Phase 4 (P1): private beta, security review, performance and field testing, observability, release hardening.

Future (P2): Bluetooth OBD-II, group events, social challenges, community heatmaps, iOS.

Out of scope for Phase 0: proprietary routing engine, public social feed, monetization, production OBD-II, machine-learning driving scores, automatic camera detection, always-on background location, public leaderboards.

## 4. Personas, roles, and access

Anonymous visitor: may view non-personal public onboarding only. Authenticated driver: owns profile, vehicles, trips and personal data. Trip owner: create trip, generate/revoke invites, start/end trip, remove members. Trip member: join by invitation, leave, share own position by explicit opt-in, view other sharing members while authorized. System administrator (future): moderation and operational support; never receive default access to private live tracks.

Authorization is server-side. Knowing a trip UUID does not grant access. Each WebSocket subscription requires authenticated active membership and is revoked on leave, removal, trip end or share revocation. Only the authenticated user may publish their own position.

## 5. Functional requirements: maps and navigation

NAV-001 MUST show map, current location and location-accuracy state after permission. NAV-002 MUST allow destination search and route preview in Phase 1; Phase 0 requires a working route demonstration using a map pin or typed coordinates if address search is not ready. NAV-003 MUST support turn-by-turn guidance using an open-source routing engine, not an in-house proprietary engine. NAV-004 SHOULD support rerouting and navigation state restoration where supported by the chosen open stack. NAV-005 MUST overlay convoy participants without obscuring route instructions. NAV-006 Phase 1 MUST overlay filtered road reports and provide route-relevant, low-distraction warnings. NAV-007 MUST expose a provider-neutral NavigationEngine interface and keep renderer/router types inside adapters. NAV-008 MUST verify OSM coverage in target Kenyan areas, self-hosted tile/routing/geocoding options, licenses and attribution, update process, hardware requirements, offline behavior, and Android compatibility before final component selection. No production dependency may rely on public OSM demo services.

Acceptance: route can be previewed and navigated on a physical Android phone; map remains usable when location is temporarily unavailable; no API keys committed to repository.

## 6. Functional requirements: community road intelligence

REPORT-001 Phase 1 MUST create reports of accident, breakdown, camera, speed bump, road closure and obstacle. REPORT-002 MUST capture geographic point, reporter, category, optional note, observed time, server creation time, status and expiry. REPORT-003 MUST show nearby and route-relevant reports, with proximity and freshness filtering. REPORT-004 MUST permit one active confirm/dismiss vote per user per report. REPORT-005 MUST prevent duplicate-vote inflation and support moderation. REPORT-006 MUST expire or downgrade stale reports by category-specific policy. REPORT-007 MUST label unverified reports as community submissions, not confirmed facts. REPORT-008 SHOULD avoid distracting prompts while moving.

Acceptance: user A reports a hazard; user B can see and confirm it; duplicate votes do not increase confidence; expired reports are excluded from active warnings. Camera warnings must comply with applicable local laws and provider policies.

## 7. Functional requirements: trips and convoys

TRIP-001 P0 MUST create a private trip with name, origin/destination, owner and status planned. TRIP-002 MUST generate an expiring, revocable invitation or join code; invitations must be unguessable. TRIP-003 MUST join with authenticated identity, reject invalid/expired invites, and prevent duplicate membership. TRIP-004 MUST enforce state transitions planned -> active -> completed or cancelled, with authorization. TRIP-005 MUST let member explicitly enable/disable live sharing. TRIP-006 MUST show latest position, accuracy and last-seen time of sharing members. TRIP-007 MUST mark stale positions rather than representing them as live. TRIP-008 MUST cease broadcasting immediately on leave, removal, sharing disable or trip end. TRIP-009 SHOULD support reconnection, fresh snapshots and sequence-aware updates. TRIP-010 SHOULD show distance between participants or group spread without encouraging unsafe pursuit.

Acceptance: two phones join one trip; both see each other's recent positions; unauthorized third phone is denied; disconnection produces stale indicator; share-off stops updates and hides location according to policy.

## 8. Functional requirements: telemetry and heatmaps

TEL-001 P0 MUST read GPS speed/position and supported accelerometer and rotation sensor streams. TEL-002 MUST timestamp using monotonic clock for sensor fusion and UTC for persisted events. TEL-003 MUST store source, device orientation/calibration status, reported accuracy and confidence. TEL-004 MUST use bounded local buffering and upload selected event batches. TEL-005 MUST identify candidate rapid acceleration, harsh braking and lateral motion events; do not claim calibrated measurements or unsafe-driving conclusions. TEL-006 Phase 3 MUST implement filtering, gravity compensation, coordinate-frame alignment and threshold calibration. TEL-007 MUST show distance, elapsed time, estimated speed summary and event counts after trip. TEL-008 future MUST support OBD-II through a TelemetrySource abstraction and per-vehicle capability discovery.

HEAT-001 Phase 3 MUST show personal visited-road heatmap based on authorized retained trip history. HEAT-002 MUST support date filtering and account deletion. HEAT-003 MUST aggregate or map-match road segments when feasible, avoiding indefinite raw-track retention. HEAT-004 MUST never expose another driver's heatmap by default.

Acceptance: a physical phone records a short trip, displays summary, and does not crash with sensors absent or unreliable; later heatmap respects owner and deletion rules.

## 9. Android client architecture

Language Kotlin; UI Jetpack Compose; architecture MVVM + repository; async Kotlin Coroutines/Flow; dependency injection Hilt; local persistence Room. Suggested modules: app, core/common, core/designsystem, core/location, core/navigation, core/network, core/database, core/sensors, feature/map, feature/trips, feature/convoy, feature/telemetry, feature/reports (Phase 1), feature/heatmaps (Phase 3).

Core interfaces: NavigationEngine, LocationSource, TelemetrySource, TripRepository, TrackingConnection, LocalTelemetryStore. Screen states: onboarding/login; home map; route preview; trip creation/join; live convoy; trip summary; reports; personal heatmap; settings/privacy.

Location sharing requires user action and an Android foreground service when appropriate. Implement runtime permissions and disclosure; do not request background location by default. Handle denied/approximate permissions, process death, service restrictions, battery optimizations, and disconnected state. Never update UI from raw high-frequency sensor events without throttling.

## 10. Backend architecture and deployment

Go modular monolith, with cmd/server, internal/config, auth, users, trips, tracking, telemetry, reports, storage/postgres, transport/http and transport/websocket. Transport handlers only decode/authorize/encode; domain services own rules. Repositories use interfaces and context-aware methods.

HTTP API over TLS; WebSocket for active trip updates; PostgreSQL/PostGIS for durable relational and geospatial records; in-memory active state only for a single-instance Phase 0 prototype. Define PositionStore and Broadcaster abstractions so Redis + pub/sub can be introduced before multi-instance deployment. Background processing may start as in-process jobs but must be idempotent and migrate to a worker when needed.

Provide OCI Dockerfile, Docker Compose for development, environment examples, database migration command, health and readiness probes, graceful shutdown, structured logs, request IDs and OpenTelemetry-ready interfaces. Never hard-code cloud-specific services. Core functionality must be self-hostable on owned hardware and must not require a paid cloud product or proprietary hosted API.

## 11. Data model and geospatial storage

users(id UUID PK, display_name, created_at, deleted_at); vehicles(id UUID PK, user_id FK, nickname, metadata, created_at); trips(id UUID PK, owner_id FK, name, origin geography(Point,4326), destination geography(Point,4326), status, started_at, ended_at, created_at); trip_members(trip_id,user_id composite PK,role,sharing_enabled,joined_at,left_at); trip_invites(id,trip_id,token_hash,expires_at,revoked_at,max_uses,use_count); telemetry_events(id,trip_id,user_id,type,magnitude,confidence,source,location geography(Point,4326),occurred_at); trip_summaries(trip_id PK,distance_m,duration_s,stats_json,computed_at).

Phase 1 adds road_reports(id,reporter_id,type,location geography(Point,4326),description,status,confidence,observed_at,created_at,expires_at), report_votes(report_id,user_id PK,vote,created_at), report_audit. Phase 3 adds retained_trip_points or compressed route segments, heatmap_aggregates with user ownership and retention.

Constraints: enforce roles/status via checks or domain validation; unique memberships; timestamps UTC; GiST spatial indexes; indexes on trip/time; idempotency where needed; transactional trip state changes. Do not persist raw live locations indefinitely.

## 12. REST API contract (initial)

GET /healthz -> 200 when process alive. GET /readyz -> 200 when required dependencies ready, else 503. POST /v1/trips -> 201 with trip id, status; authenticated. GET /v1/trips/{tripId} -> 200 for member, 404/403 according to policy. POST /v1/trips/{tripId}/invites -> 201 owner only, returns one-time visible invite token. POST /v1/trips/{tripId}/join -> 200 with valid invite; authenticated. POST /v1/trips/{tripId}/start -> 200 owner only. POST /v1/trips/{tripId}/leave -> 204 member. POST /v1/trips/{tripId}/end -> 200 owner only for an active trip. POST /v1/trips/{tripId}/cancel -> 200 owner only for a planned trip; cancellation is terminal. GET /v1/trips/{tripId}/members -> 200 member. POST /v1/trips/{tripId}/telemetry -> 202 authenticated member with bounded batch. GET /v1/trips/{tripId}/summary -> 200 authorized member.

Use JSON, UUIDs, RFC3339 timestamps, consistent errors {code,message,request_id,details?}, explicit validation, pagination for collections, idempotency keys for retry-prone creates where applicable. Generate and maintain contracts/openapi.yaml with example requests/responses and status codes. API must not accept arbitrary user_id for ownership; derive from verified identity.

## 13. WebSocket contract and behavior

Endpoint GET /v1/trips/{tripId}/stream with short-lived trip-scoped credential or authenticated handshake; do not place long-lived bearer tokens in URL. Message envelope: {type,version,event_id?,sequence?,payload}. Client->server: position.update with latitude,longitude,speed_mps,heading_degrees,accuracy_meters,recorded_at. Server->client: trip.snapshot, participant.position, participant.joined, participant.left, trip.ended, error.

Server derives trip_id and user_id from authenticated connection; checks active membership and sharing_enabled before publishing; validates latitude [-90,90], longitude [-180,180], finite values, timestamp freshness, max message size and rate. Maintain per-trip sequence or timestamp ordering and drop older updates. On connection send snapshot of currently sharing members. Heartbeats/ping-pong and reconnect with exponential backoff; on reconnect reauthorize and resync snapshot. Revocation must disconnect or stop data access promptly. Slow consumers are bounded and dropped safely. No location broadcasts to nonmembers.

## 14. Privacy, safety, security and compliance

All live location sharing is opt-in, visible, and revocable. Never share positions when no active trip or sharing disabled. Display last-seen and accuracy; do not imply perfect tracking. Use TLS/WSS, secure credential storage, hashed invite tokens, rate limiting, input validation, and least-privilege DB access. Sensitive location logs must be redacted; no raw GPS traces in general application logs.

Define retention policy before production: active position TTL, raw sensor buffer expiry, trip history retention options, deletion/export flows, backups and purge. Obtain consent/notice for processing precise location and telemetry. Assess Kenyan Data Protection Act obligations and the policies of the chosen app distribution channel; if Google Play is selected, review its location policies. Obtain legal review before public release.

No driving-time gamification rewarding speed, risky maneuvers or racing. Prefer audio alerts, minimal on-screen interaction and a passenger-safe UI. Camera alerts must be legally reviewed per jurisdiction.

## 15. Nonfunctional requirements and test targets

Availability: Phase 0 is single-instance developer prototype, no production SLA. Latency target: p95 server receipt-to-broadcast under 500ms on stable network, measured not assumed. Sampling target: GPS every 1–3 seconds during active sharing, adaptive to movement/battery; stale indicator after configurable ~10 seconds. Backpressure: bounded queues, drop obsolete position updates before essential state messages. Reliability: reconnection and correct stale indicators after mobile network loss. Battery: measure 30–60 minute test sessions on two devices; establish acceptable budget from results. Accessibility: readable contrast, screen-reader labels, large tap targets, reduced distraction. Observability: request latency, websocket connections, position update delay, auth denials, DB health, crashes and battery impact.

Testing: Go unit tests for state transitions, auth, validation and telemetry; DB integration tests with PostGIS; WebSocket multi-client integration tests including unauthorized and revoked members; Android unit/instrumentation tests; field tests for GPS accuracy, network loss, device rotation, background restrictions and battery. CI runs lint, tests, migrations, build and security scanning.

## 16. Phase 0 implementation milestones and acceptance gates

M0 Repo and environment (days 1–2): monorepo, Kotlin app skeleton, Go API, Docker Compose PostGIS, migrations, CI, README, .env.example. Gate: fresh clone starts API and DB; tests pass.

M1 Open mapping and navigation spike (days 3–4): evaluate MapLibre Android with OSM-derived map data and self-hosted open routing (Valhalla is the initial candidate) in Kenya for coverage, overlays, licensing/attribution, update process, offline behavior, hardware requirements and geocoding options. Gate: one physical Android device previews and follows a test route through the self-hosted stack; ADR documents evidence, selected components, alternatives and operating requirements.

M2 Trip domain (days 5–6): development-only signed identities, create/invite/join/start/end, persistence, authorization tests. Gate: two distinct users join one trip; unauthorized user cannot read it.

M3 Real-time hub (days 7–8): WebSocket auth, snapshot, position updates, broadcasts, staleness and disconnects. Gate: two authenticated test clients exchange positions; revoked member receives none.

M4 Android convoy (days 9–10): foreground location, WS connection, two markers, permission UX, reconnect and stop-sharing. Gate: two real phones see each other and correctly mark stale positions.

M5 Telemetry (days 11–12): sensor capture, local bounded buffer, event extraction, batch upload, summary. Gate: short test drive yields summary and gracefully handles missing sensor.

M6 Validation (days 13–14): field test, battery and latency measurements, security checks, docs, bugs. Gate: demo script reproducible and P0 acceptance suite passes.

Dates are estimates for focused solo development, not promises.

## 17. Repository layout and delivery requirements

project-drive/

  AGENTS.md

  README.md

  apps/android/{app,core,feature}/

  services/api/{cmd/server,internal,migrations,Dockerfile,go.mod}/

  contracts/{openapi.yaml,websocket/events.json}

  infra/compose.yaml

  docs/{SPEC.md,ARCHITECTURE.md,ROADMAP.md,adr/,navigation-evaluation.md,phase-0-test-plan.md}

  .github/workflows/

  .env.example

  Makefile

Agent must commit or produce complete source files (no placeholder pseudo-code passed off as implemented), include dependency lockfiles, migration files, executable tests, commands for local start, Android build instructions, sample configuration, and a short status report listing completed criteria, tests run, blockers, and next steps. No secrets, vendor keys, or credentials in git.

## 18. Open decisions and assumptions

Navigation stack: use open-source software and OSM-derived open data; select renderer, self-hosted tiles, router and geocoder only after the live Kenya spike and license/data/hardware review. MapLibre and Valhalla are candidates, not approved final choices. No paid third-party service is required. Identity: production solution choice deferred but MUST be self-hostable and open source with no paid hosted dependency; development identity MUST be disabled outside local/test builds. Redis: deferred until multi-instance scaling; in-memory tracking only for single-instance prototype. Trip invite UX: short code vs deep link to be validated; backend supports expiring token. Route history: retention and precision to be defined with privacy policy before Phase 3. Telemetry thresholds: calibration required; no universal hard-coded unsafe-driving score. Location permissions: foreground-first; background permission only with documented product need and platform-policy review. Push notifications, offline maps, iOS and OBD-II remain future decisions.

## 19. Codex execution protocol

1. Read AGENTS.md and this SPEC in full; inspect existing repository before modifying. 2. Create a concise implementation plan and list unresolved blockers. 3. Work one milestone at a time in order M0 through M6. 4. Keep the code compiling after each milestone. 5. Add tests alongside functionality and run them. 6. Document architecture decisions, open-source licenses, data obligations, and component-specific constraints. 7. Never invent SDK methods, package versions, current pricing, or verified platform behavior; consult official docs when available and isolate uncertain integrations. 8. Never silently omit security checks for convenience. 9. If credentials or Android SDK access are unavailable, implement testable interfaces and clearly mark the blocked integration. 10. Report files changed, commands executed, test results, limitations and exact next step.

## 20. Initial Codex prompt

Implement Project Drive Phase 0 in this repository. First read AGENTS.md and docs/SPEC.md completely. Do not attempt Phase 1+ features. Begin with M0: initialize the monorepo, Go API with /healthz and /readyz, PostgreSQL/PostGIS Docker Compose, migrations, Kotlin/Compose Android app skeleton, tests, CI, and developer documentation. Then report what is complete and how to run it. Before proceeding to M1, evaluate an open-source OSM-based map/routing stack, licensing, Kenya routing, self-hosting requirements and device/hardware needs, and present the recommendation. Preserve a provider-neutral NavigationEngine interface. Never claim untested hardware behavior is verified.



## Appendix — Architecture diagrams

See [ARCHITECTURE.md](ARCHITECTURE.md) for system context, container/module boundaries, live tracking message flow, telemetry pipeline, ER relationships, deployment topology, data flows, and architectural decisions. The editable diagram sources are under `docs/architecture/`.
