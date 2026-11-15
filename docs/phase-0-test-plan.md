# Phase 0 validation plan

Updated 15 November 2026. Continue software milestones and collect remaining field
checks into [one coordinated outing](consolidated-field-test.md), as requested by
the owner. Do not require separate feature drives. Software completion and physical
acceptance remain distinct; deferred checks are not passed checks.

## Automated and desktop evidence

| Area | Current evidence / reproducible entry point |
| --- | --- |
| M0 build/environment | Existing local and GitHub CI; `make doctor`, backend integration/health and manifest rendering. Fresh checkout is separate from newly provisioned hardware. |
| M1 maps/navigation | Self-hosted regional graph, five fixed route samples, tile/glyph smoke; native UI/search/shared-link/camera/location checks. `scripts/navigation-smoke.py BASE_URL`; see [M1](milestones/M1.md). |
| M2/M3 private domain/hub | Real PostGIS REST/WebSocket clients cover identities, membership, lifecycle, invitations, revocation, stale/TTL, reconnect, bounded queues and process restart. `make integration`; see [M2](milestones/M2.md) and [M3](milestones/M3.md). |
| M4 Android convoy | One real phone plus two emulator software checks already passed; not a two-real-phone field pass. See [M4](milestones/M4.md). |
| M5 journal | JVM estimator, native Room migration/expiry/binding/sensor and emulator upload/recovery checks passed. Updated phone retained normally completed summaries and nonzero acceleration/braking candidate details on the Tigoni drive. See [M5](milestones/M5.md). |
| Background journeys | Explicit service/notification controls, native emulator screen-off, Activity replacement and interrupted checkpoint recovery passed. See [background evidence](background-journeys.md). |
| M6 server performance | `make latency`: opt-in 100-update/two-peer loopback/PostGIS server receipt-to-write baseline, default limits and Go race detector. Not mobile/client/render or high-load latency. |
| Security/operations | `make security`, `make compose-config`, `make homelab-config`; public protected-path denial, immutable images/PVC preservation, map packaging/source integrity. See [desktop review and demo](m6-desktop-validation.md). |

Existing Android baseline is 40 JVM tests and 23 distinct native checks. Use the
selected Chris Banes managed wrapper for any agent-initiated Gradle invocation.
Repeat targeted checks when code, inputs, failures or an unresolved concern warrant
it. Preserve owner device data, permissions and rotation preferences; fixed public
fixtures and synthetic GPS belong on disposable emulators.

## One field acceptance bundle

The [combined run sheet and result form](consolidated-field-test.md) is the current
procedure for all remaining physical checks. Its prerequisites include a second
real phone and legitimate production authentication before public convoy testing.
The public API currently rejects development identities; an OSM login is unrelated.
Do not change that guard to get a field pass.

- [x] Owner reports navigation worked as expected with no wrong turns on the Tigoni journey.
- [x] Real-device journal capture and longitudinal candidate persistence after the detector fix; saved details match counts, no dropped details, both new recordings completed normally.
- [ ] Explicit arrival and remaining specific road/access/one-way/elevated-road observations; final mapping ADR acceptance.
- [ ] Prolonged physical background and screen-off navigation/recording/sharing.
- [ ] Two real phones: positions, freshness, mobile loss/reconnect, explicit off, removal/end and denied/approximate location behavior.
- [ ] Two-device 30–60 minute battery recordings under stated conditions, with charging and screen state qualified.
- [ ] Resource-representative latency, remaining operations/security review and final reproducible P0 demo acceptance.

The [Tigoni data review](device-tests/2026-11-15-tigoni.md) is retained evidence,
not a request to repeat another event drive. Event estimates remain uncalibrated;
ordinary zero counts do not prove a defect. Do not provoke manoeuvres to test them.

## Connectivity and historical procedures

The app now uses public HTTPS for maps/routes without workstation forwarding. A
loaded route and cached tiles do not constitute full offline maps; uncached map
areas and new route requests need the server. Public health/readiness/metrics remain
blocked. Production sign-in, automatic rerouting, voice guidance and full offline
map downloads are not implemented by this acceptance plan.

Historical reports under `docs/device-tests/` and `docs/milestones/` retain their
original commands/results. Foreground-only rotation/background expectations from
before the explicit journey service are superseded by the current design.
Legacy loopback harnesses need the documented local debug variant and isolated
local identities; never run them against public production authentication or reset
an owner phone's permissions to satisfy a fixture. Keep private record content,
coordinates, identifiers, pairing codes and personal-position screenshots out of Git.
