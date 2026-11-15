# M6 desktop validation and reproducible demo

15 November 2026. This is the software/operations portion of M6. The
[combined field session](consolidated-field-test.md) retains hardware-dependent
checks. Passing this work alone does not complete Phase 0 or approve a public beta.

## Server latency baseline

`make latency` runs the existing real PostGIS integration suite, migration
idempotence and health checks, then an opt-in two-peer WebSocket measurement:

```sh
make COMPOSE='docker compose -p outset-m6-validation -f infra/compose.yaml --env-file .env.example' latency
```

Use an unused local Compose project and free loopback ports 5432/8080. This command
creates local disposable verification data using the example local-only credentials;
it does not modify homelab data or enable public development identities. Clean up
only this task-owned project afterward, never another project's volumes.

The test generates ephemeral signing keys in memory, creates two consenting test
members in an isolated disposable PostgreSQL schema, and exchanges 100 synthetic
fixed-public-point updates over 50 seconds. Each participant sends at 1 Hz; default
tracking limits and authorization remain active. Both clients read every update.
The measurement runs with the Go race detector and prints aggregate durations only.

Timing starts at `Hub.Publish` entry immediately after the handler reads a complete
WebSocket text frame, before acquiring the hub gate. It ends after the authorized
recipient's actual WebSocket `Write` returns successfully. It includes hub contention,
decode/validation, PostgreSQL membership reconciliation, queueing, final authorization,
JSON serialization and socket write. It excludes incoming-frame reading and any
client receipt/render acknowledgement. Self echoes, snapshots, rejected, expired or
revoked deliveries are excluded. Failed peer writes are counted separately.

The process retains at most 1,024 durations and lifetime success/failure counters,
with nearest-rank p50/p95/max over the retained window. No IDs, coordinates, wall-clock
timestamps or frame bodies are retained in these metrics. There is no new public
metrics endpoint, protocol field, database table or third-party analytics service.

The baseline requires 100 successful peer samples, zero failed writes and p95 below
500 ms. Results establish only a two-peer, same-host/loopback PostGIS baseline;
homelab resource limits, mobile connectivity, high fan-out and sustained load need
their own measurements. They must not be described as measured mobile latency.

## Security and privacy review

| Control reviewed | Evidence / boundary |
| --- | --- |
| Development identity isolation | Config and identity tests require local/test environment. Homelab has no development public key. Public private-trip probes must remain 401/no-store. Production sign-in is still absent. |
| Per-operation membership and revocation | Existing real PostGIS REST/WebSocket tests cover outsider denial, ownership, sharing off, removal/leave/end, queued-write revocation, expiry and restart without retained positions. Timing changes preserve the same delivery gate. |
| Bounded inputs/state | Existing tests cover message/rate/queue limits, slow consumers, stale/TTL expiry, recording/event bounds, retention and idempotency. Latency storage is independently bounded; errors do not count as successful samples. |
| Android data/consent | Source review confirms separate explicit recording/sharing, unexported non-sticky journey service, visible notification Stop, no background-location permission/boot receiver, backup disabled and Keystore-protected local test credentials. Previously passed native tests cover these paths; no new Android source change is needed here. |
| Upload and logs | Local `field_test` diagnostics are removed before upload; telemetry has no raw path/sensor arrays. REST/stream tests cover own-only summaries and no raw position persistence. API logs redact map assets/trip paths; no new position or identity labels are logged. |
| Transport/deployment | Default app uses HTTPS; debug cleartext is loopback-only. Map/API Services remain internal behind restricted tunnel paths; operational endpoints are not public. Nonroot, read-only runtime, dropped capabilities and immutable GitOps image promotion remain in the existing manifests. |
| Dependencies | Run the pinned Go vulnerability scan; retain existing Android lint/dependency/build evidence unless source or inputs change. A green scan is not a penetration test or legal/privacy certification. |

Remaining release risks: production identity, complete beta security/privacy review,
database restore exercise, map-data refresh with a newly downloaded snapshot,
clean-machine Android SDK provisioning, multi-device field acceptance and resource-
representative performance. No legal approval or production-readiness claim is made.

## Fresh checkout and map operations

The reproducible developer path is `make setup`, `make doctor`, `make integration`,
`make security`, `make compose-config` and `make homelab-config`, followed by Android
build/unit/instrumentation compilation/lint using the documented Gradle entrypoint.
Agents use the selected Chris Banes managed Gradle workflow for any new Gradle run.
Install APKs in place, with no active journey and without clearing phone data.

Verify startup from a fresh detached checkout with a separate Compose project and
database; record existing toolchain/cache reuse. This is a clean-source deployment
exercise, not a newly provisioned machine or proof that all external downloads work.
Never use `make clean` on the owner's normal project as part of validation.

Map refresh remains immutable: build a new snapshot directory, build tiles/router
from the same source, rebuild the name gazetteer, verify licenses/source hashes,
package to a new directory and check every digest before promotion. Both runtime
map subPaths must refer to the same immutable map release; the search release must
record the matching source hash. Retain old directories/PVCs and promote via GitOps.
Follow [the map runbook](navigation-evaluation.md) and [place search](place-search.md).

An offline packaging rehearsal can reuse the existing snapshot: run
`scripts/package-map-data.sh` with a new task-owned `MAP_RELEASE_DIR`, verify
`sha256sum -c SHA256SUMS`, MBTiles integrity/tile count and the original source hash.
This checks packaging and preserves the active dataset; it is not a fresh rebuild,
live promotion, routing-quality review or restore test.

## Demo sequence

1. Show public map/place search/shared-link selection, real current-location UX,
   preview pan/zoom and Recenter, attribution and clearly labelled simulated preview.
2. Show journal capture/finish and a derived saved summary, with the separate upload
   consent and current sign-in limitation. Use disposable test data for demonstrations.
3. Run real local PostGIS trip/stream/telemetry acceptance and `make latency`;
   explain that authenticated software peers are not the two-real-phone field gate.
4. Show explicit journey notification controls and the existing native background,
   screen-off and process-recovery test evidence; collect physical proof in the one
   combined outing. Never show owner-private positions/records on a public demo.
5. Record CI, immutable image/Flux readiness, preserved PVCs and a public fixed-point
   map smoke. Keep operational endpoints and development authentication private.

## Execution record

Current results are recorded in [M6](milestones/M6.md). Failed or unexecuted gates
stay visible; later milestones may proceed with the owner's consolidated-field-test
preference without relabelling those gates as passed.
