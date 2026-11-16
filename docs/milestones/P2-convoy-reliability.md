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
checks (0.314 seconds). The fixtures use no owner account and publish no live point.
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
GitHub delivery and journal-preservation evidence will be appended after promotion.

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

Transport reference: [OkHttp 4.12.0 ping and shared-client behavior](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt).
