# Phase 1 — Community road reports

Owner authorized the next milestone on 15 November 2026 after the first real
Google sign-in and restart restoration passed. Remaining hosted account/convoy
acceptance stays open in [authentication](P1-authentication.md); starting this
increment does not declare authentication or M6 complete.

## Delivered scope

- Authenticated observations: accident, breakdown, camera, speed bump, road closure
  and obstacle. A selected map pin, optional 240-character note, observation time,
  creation time, expiry and lifecycle status are stored in homelab PostGIS.
- The full-screen map remains pannable. Road reports live in a fourth tools-sheet
  tab. Choose report pin temporarily changes long-press handling without changing
  the navigation destination. A draft pin is visible, and publication requires a
  parked/passenger confirmation. Known movement above 3 m/s disables changes;
  preview playback cannot publish, query or generate guidance warnings.
- Nearby lookup is off initially and resets after process death or account
  change. Its disclosure explains transmitting the current location to Outset's
  server while visible, separately from publishing a community pin. Only a fresh
  location with an accuracy estimate of 50 m or better is used. Queries and
  response bodies are not logged or stored as journeys.
- Enabled lookup fetches the nearest 100 reports in 10 km at most every 30 seconds
  while foreground; the API permits 100 m–20 km. Queries use POST bodies, not URL
  coordinates. Results expire locally using server time plus monotonic elapsed
  time and disappear after 90 seconds without a fresh snapshot. No background
  report query or additional Android permission is introduced.
- Each other account can confirm or dismiss once per report. Repeating a vote is
  a no-op; changing it replaces the previous vote. The author cannot self-vote.
  Reporter/account identifiers are omitted from report responses. Community
  labels are unverified, supported (at least two confirmations), or disputed
  (at least three dismissals exceeding confirmations), never verified facts.
- Owners can withdraw their report after confirmation; its note is scrubbed and
  it immediately leaves active queries. Disputed reports remain labelled on the
  map and are suppressed from guidance warnings. This supplies initial community
  downgrade and author withdrawal; a trusted moderator dashboard/appeal workflow
  is not implemented and remains a private-beta readiness item.
- A single passive visual warning can appear during real foreground guidance for
  the nearest observation in the next 1.5 km, within 100 m of the route. The phone
  must be within 60 m of the route with a fresh usable fix. Behind-route, expired,
  disputed and off-route observations do not warn. This is geometric proximity,
  not proof of road direction, lane or legal access; grade-separated/parallel-road
  false positives need field evaluation. No voice or background warning is claimed.
- Camera observations are supported as community pins, but camera navigation
  warnings are deliberately excluded pending the spec's jurisdiction review.

## Server policy and operations

| Category | Lifetime from observed time |
| --- | --- |
| Breakdown | 1 hour |
| Accident / obstacle | 2 hours |
| Road closure | 6 hours |
| Camera | 7 days |
| Speed bump | 30 days |

Observations must be within the last 24 hours, no more than 30 seconds in the
future, and still inside their category lifetime. Voting never extends expiry.
Create retries use an account-scoped UUID idempotency key with identical content;
changed content using that key conflicts. The Android draft/retry intent is kept
in the ViewModel, surviving rotation but not process death; there is no unattended
offline upload/outbox. An uncertain request should be retried before editing or
restarting. A process-death retry cannot guarantee deduplication.

Persistent per-account limits are ten new reports/hour and sixty changed
votes/hour. Transactions serialize each actor and lock each mutated report, so
concurrent retries cannot inflate counts or race withdrawal. Reads require the
same live identity verification as convoy operations, return no-store data, use a
GiST geography index and cap results at 100. Request bodies and operation duration
are bounded. Report APIs contain no administrative bypass or public anonymous
read. HTTP logs normalize report IDs and omit query strings and request bodies.

Migration `0005_road_reports` is additive: reports, votes and minimal change audit.
The existing cleanup loop deletes reports seven days after expiry, cascading to
votes and audit. Withdrawal shortens retention to approximately seven days.
Active observations last at most 30 days; database copies in backups follow the
existing backup lifecycle and are not claimed to be erased by live-table cleanup.
The server does not persist query locations, route geometry or phone GPS trails.

Use the existing CI → immutable GHCR SHA → homelab-deploy → Flux release path.
The existing `/v1/*` tunnel already covers these authenticated endpoints; no
Cloudflare route, public admin endpoint or database exposure is added. Preserve
DB/maps PVCs and current recordings. A backend rollback leaves this additive
migration intact; do not drop tables as an automatic rollback.

## Verification ledger

On 16 November, Go vet and race tests, the complete disposable PostGIS suite
(including `TestRoadReportsIntegration` in the default integration command),
security scan, Compose configuration and Kustomize rendering passed. No Go
vulnerabilities were found. OpenAPI YAML and local references validated. The
focused PostGIS report acceptance test passed too, including
concurrent votes, identity/ownership denial, retry conflicts, category/input
validation, spatial filtering, expiry, withdrawal, quotas, retention cascade and
redacted logging. The first Android aggregate build passed with one unnecessary
safe-call warning; the cited source was inspected and corrected before final
validation. A later interaction review corrected Show on map so it closes the
sheet without generating another sheet-open request.

Managed Android questions/results:

1. “Do the road reports APKs compile and do route-warning policy tests and lint pass?” Passed; one unnecessary safe-call compiler warning was corrected.
2. “Do the final report APKs, unit checks and lint pass after removing the compiler warning?” Passed without warnings.
3. “Do the final road-report APKs, 47 unit checks and lint pass with the map-return correction?” Both APKs, all 47 JVM checks and lint passed with strict dependency verification. A tooling warning reports SDK XML v4 versus the command-line reader's v3 support; it did not fail the build. No dependency/platform change was introduced to suppress it.

The first workflow's `/tmp` ledger was lost across the workstation restart; its
earlier bounded results are recorded above, and it cannot be claimed finished.
No Gradle owner remained. The final validation used a new managed workflow
`0d682b7df16f067a6476de9d0c7c3971`; it was finished, removing only wrapper-owned logs.

The final APK and test APK installed in place on the physical Samsung phone.
Four report UI checks and three account UI checks passed. Four existing map/tool
checks encountered the Android lock screen and are pending an unlocked rerun;
they are not claimed as passing. The fixtures do not use owner credentials or
publish observations. Six owner recordings were present in the private
pre-upgrade SQLite snapshot, whose integrity check passed.

A private pre-migration homelab database dump passed its archive catalogue check;
it was not restore-tested. The existing restricted deployer identity was renewed
for one hour after expiry, without RBAC changes. The authorized operator identity
performed the database backup because the restricted identity cannot exec into
the database pod. DB/map PVC identities were recorded before promotion.

GitHub CI, Flux rollout, migration application, post-upgrade preservation and
unlocked phone smoke results will be recorded after delivery. No fake observation
was published to the live community feed.

Physical route relevance, false positives on parallel/elevated roads, warning
readability and mobile-network freshness join the existing consolidated outing
on **25 November 2026**. This milestone's software delivery does not close those
field gates.

Implementation references: [PostGIS geography distance query](https://postgis.net/docs/ST_DWithin.html)
and [MapLibre rendered-feature hit testing](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-libre-map/query-rendered-features.html).
