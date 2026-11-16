# Phase 2 — Convoy reliability

Owner authorized the next milestone on 16 November 2026. This increment hardens
the existing private convoy; it does not close the consolidated field gates or
the remaining hosted Google account acceptance.

## Behavior

- One suspending `TrackingStream` runs inside the consent-owned `ConvoySession`
  job. Cancellation closes the current flow/socket and cancels its snapshot and
  renewal timers. No detached retry job is created. The existing journey service
  continues to own screen-off operation; process restart never restores consent.
- Each attempt obtains a current credential, requires the original account, and
  reauthorizes with the backend. A new stream must supply a snapshot within ten
  seconds. The deadline cancels the attempt even if socket cancellation alone
  would not complete a connection. Positions cannot publish before the snapshot.
- Network/IO failures, HTTP 408/425/429 and 5xx responses retry with exponential
  backoff: 1, 2, 4, 8, 16, then 30 seconds, plus 0–500 ms jitter. A valid snapshot
  resets the backoff. Unrecognized/protocol failures and rejected access stop
  sharing instead. WebSocket policy/protocol close codes fail closed.
- Hosted token renewal starts 45 seconds before expiry. The auth repository
  refreshes credentials within its existing 60-second window. A temporary refresh
  failure retries without clearing sharing consent; no expired token is used to
  open a socket. Actual account removal, revocation or sign-out still stops the
  session. Local development identities continue to stop at expiry.
- During an outage, the UI says sharing is enabled but offline, gives the retry
  interval and hides participant markers. A fresh authorized snapshot replaces
  the roster after recovery. Connected peers still age to stale at the server's
  threshold (normally ten seconds) and expire at its TTL (normally thirty).
  No disconnected marker is presented as live or resurrected from disk.
- The dedicated, shared OkHttp stream client sends pings every fifteen seconds
  to detect a silent dead connection. This does not change ordinary HTTP clients.
- Stop-sharing cancels publication locally immediately. Its pre-existing durable
  server-cleanup intent survives uncertain responses. Failed cleanup now backs
  off to thirty seconds instead of attempting a request every second; explicit
  user actions can still request immediate cleanup. Cancellation is propagated
  from both cleanup and startup restoration.
- Existing owner/member roles, bounded expiring invitations, removal, leave,
  end/cancel and server-side authorization remain in force. No permissions,
  public routes, schema, paid dependencies or retained location history are added.

## Verification

Eight new JVM checks exercise offline refresh, exponential/reset backoff, renewed
credentials, cancellation while waiting, snapshot timeout, successful snapshot
deadline cancellation, terminal authorization/protocol failure and account switch.
Two Android socket checks use a loopback WebSocket fixture with synthetic
credentials and a public synthetic point: 503 recovery, abrupt disconnect, fresh
empty snapshot removing an old marker, terminal sharing revocation, and a denied
handshake. They never use the owner's account or publish a real location.

All 55 JVM checks, both debug APK builds and Android lint passed on 16 November.
The final in-place installation passed nine native checks on the connected Samsung
phone: two recovery transport, four convoy safety and three isolated auth-session
checks (0.314 seconds, then 0.338 seconds on the final mobile source). A separate
6.306-second phone check passed public HTTPS maps, routing/search and private
HTTP/WebSocket denial: ten distinct native checks passed. The fixtures use no
owner account and publish no live point.
The first JVM run found an assertion that incorrectly required exception object identity across
coroutine stack recovery. It was changed to compare type/message; this was a test
assertion failure, not an authorization failure.

Managed Gradle workflow `13f2df4f7eed81b186f8b4cef3d7f23b` used:

1. “Do convoy recovery, cancellation and access-boundary unit tests pass?” Initially failed the exception-identity assertion described above; corrected before release.
2. “Do the recovery tests, both APKs and Android lint pass after correcting the exception assertion?” Passed without warnings.
3. “Do the final recovery and cleanup changes pass all unit checks, lint and APK builds?” Passed: 55 unit checks, both APKs and lint, without warnings.

All runs used the managed wrapper with `:app:testDebugUnitTest`; aggregate runs
also used `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`. The
workflow was finished after the final pass, removing only wrapper-owned logs.
Final review also added a no-pending-intent guard before fetching credentials for
cleanup, preventing an idle offline account from falsely showing cleanup pending.
Managed workflow `e513729ea888f26efe26bb2ec6192255` answered “Does the idle-cleanup
guard pass unit tests, lint and final APK builds?” with a clean pass of all 55
checks, lint and both APKs. It was finished, removing only wrapper-owned logs.
Six owner recordings passed SQLite integrity and an unchanged private table
hash/count after both in-place upgrades. The installed APK's SHA-256 matches the
final local artifact. No journey service remained active after testing. The final
Android source is `c702c55`; subsequent source `003fc85` changes only Compose/CI
startup and documentation, so the installed Android code is unchanged.

## CI startup correction

CI on mobile source `c702c55` failed before Android validation: the fresh Compose
database reported healthy on its temporary Unix setup socket and migrations
received TCP connection refused. The preceding `ac54552` CI passed, confirming
the intermittent nature; the failure was not hidden by rerunning unchanged code.
A disposable fresh database with an eight-second init hook reproduced the failure.
Changing Compose readiness to `pg_isready -h 127.0.0.1` passed the same delayed
startup and all five migrations. Only that isolated fixture's volumes were removed.
Compose/Kustomize validation passed. CI now includes bounded db/migrate failure
logs. No homelab database/probe/schema or Android source was changed by this fix.
The [official image entrypoint](https://github.com/docker-library/postgres/blob/master/docker-entrypoint.sh)
starts its temporary setup server with TCP disabled.

## Delivered release — 16 November 2026

Source `003fc85106ed5100fca62b417b86dfc74535209e` passed
[CI 43829297217](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43829297217)
and [deployment 43830516899](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43830516899).
Flux is Ready at `homelab-deploy@sha1:9fcf5603d1305fd518919bdafb9c858638bfeaa5`.
The API deployment has one updated/ready replica running
`ghcr.io/gibson-gichuru/project-drive:003fc85106ed5100fca62b417b86dfc74535209e`.
Its new pod has zero restarts. DB/router/tiles are Ready, with unchanged historical
restart counts of 2/1/1. Database/maps PVC UIDs, bound volumes and Bound state match
the private pre-release record; no live database migration or credential change
was required for this increment. Routine reads used the existing restricted
deployer; the existing operator identity read Flux status. Neither RBAC nor public
routes were changed.

Post-rollout unauthenticated private-trip HTTP and WebSocket requests returned
401. Five fixed public Kenya routes, one vector tile and a Latin glyph range
passed the HTTPS smoke script. An initial Python-default-user-agent probe hit the
existing Cloudflare 1010 filter; the explicit smoke client, curl and Android's
public endpoint checks passed without any Cloudflare policy change.

**Software delivery passed: pushed, deployed and installed. Phase 2 field and
hosted-account acceptance remain open.** Documentation-only evidence commits use
`[skip ci]` and do not require rebuilding the unchanged app or API image.

## Remaining acceptance

Use the [25 November consolidated run](../consolidated-field-test.md). Record two
real participants, network loss/recovery, immediate remote share-off/removal/end,
background/screen-off duration and a measured 30–60 minute battery result against
an agreed budget. Emulator/socket fixtures do not establish those field results.
Complete actual hosted private-convoy, sign-out/revocation and account-change
acceptance from [authentication](P1-authentication.md); synthetic accounts do not
close those gates. An offline phone cannot deliver an immediate server-side stop;
local publishing stops immediately and the server's last point expires by TTL.

The server remains a single instance with ephemeral positions. Multi-instance
Redis/pub-sub, voice convoy messages and automatic sharing on Android Auto
connection are outside this increment. Phase 2 remains open until acceptance.

## Changed files and repeatable checks

- Android core: `core/convoy/TrackingStream.kt`, `TrackingConnection.kt`;
  session/UI: `feature/convoy/ConvoySession.kt`, `ConvoyPanel.kt`.
- Checks: JVM `TrackingStreamTest.kt`, native `ConvoyRecoveryTest.kt`; existing
  `ConvoySafetyTest`, `AuthSessionTest` and opt-in `PublicEndpointTest` were run.
- Delivery: `infra/compose.yaml`, `.github/workflows/ci.yml`.
- Guidance: README, roadmap, this report and consolidated field run sheet.

From `apps/android`, use the managed Gradle wrapper for
`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.
Install both APKs in place with `adb -s DEVICE install -r -t APK` while no journey
is active. Run `am instrument -w -e class` with the three native class names above
and runner `org.projectdrive.app.test/androidx.test.runner.AndroidJUnitRunner`.
Run `PublicEndpointTest` separately with `-e publicEndpointChecks true`; it sends
only fixed public samples. `make compose-config homelab-config` checks deployment
rendering. Full CI also runs Go race/PostGIS/security and real standalone Supabase
acceptance. The delayed fresh-volume startup check is described above.

Transport reference: [OkHttp 4.12.0 ping and shared-client behavior](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt).
