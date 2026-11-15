# Phase 1 — Supabase authentication

## Current direction — 15 November 2026

The owner selected Google sign-in through hosted Supabase Auth and canceled the
homelab SMTP sender. Supabase owns account creation, Google OAuth, token exchange
and refresh. The Android integration is being moved to the Supabase Kotlin auth
client. Go/PostGIS remains the application and convoy authorization boundary.
Google sign-in must never enable location sharing or uploads automatically.

The separate Outset Free project `ghddrkgouwtagsgjqcxy` is healthy in the owner-approved organization (Mumbai). The existing Job Hunter project is untouched. Google Cloud project `outset-514815` is isolated too; consent branding is saved in testing mode after the owner approved Google's User Data Policy. Google provider
configuration and a complete browser-to-app sign-in remain acceptance gates.
No paid plan, email service or delivery workaround is required by this decision.
Google is the owner-selected optional account provider; solo maps and local
recording remain available without an account.

## SMTP retirement

The production sender manifests, submission/queue bootstrap, DKIM key generation,
node logging bootstrap and sender acceptance job have been removed from source.
The normal CI → immutable image → homelab-deploy → Flux path removes its Service
and Deployment. After reconciliation the operator removes only the sender's
queue PVC, private policy and signing Secret, plus the exact added node logging
include. Application/map volumes, Auth database and other Secrets are preserved.
The held test message must be discarded with the retired queue, never resent.
Cleanup source `a9f810f` passed CI 43728005551 and deployment 43728701411.
Flux applied `06f836f14b06b3357b9097c8dad8e32b95e1282c`; its API image matched
that source. The SMTP Deployment/Service and pods were absent before operator
removal of the queue PVC, policy and signing Secret. Its local-path PV was
subsequently absent. The four task-owned DNS records were removed after explicit
owner confirmation, leaving eight other domain records. The exact Outset rsyslog
include/snippet was removed on all three nodes and existing profiles reloaded.
The task's separate mail-key backup and held-message identifier were removed;
application/Auth archives and original node profile backups remain preserved.
DB/maps retain their original Bound PV identities. No test email was resent.

Mailpit in `infra/auth.compose.yaml` remains a disposable, non-relaying test
fixture for provider/session protocol checks. It is not a deployed email service.

## Acceptance and previous evidence

The earlier self-hosted Supabase foundation passed real disposable provider,
PostGIS and WebSocket checks, including refresh/revocation, isolation and logout.
Those results do not establish that Google OAuth works. The earlier app was
installed in place on the physical phone: all six saved recordings and app table
content hashes were preserved, and seven isolated Auth/UI/Keystore checks passed.
Four separate emulator background journey checks passed. No owner credentials
were used and no physical/head-unit test is inferred from these software checks.

Outstanding Phase 0 field gates stay consolidated on 25 November 2026; see
[M6](M6.md). Authentication acceptance additionally requires Google browser return,
encrypted session restoration, authorized convoy access, sign-out/revocation and
account-change sharing-off behavior with the hosted provider.

## Earlier Android verification ledger

These questions describe the previous email-auth build, not the new Google flow.

Managed Gradle workflow `b93f0e94f991ecd707be466d99d125a5` used the selected
Chris Banes wrapper, always as standalone commands. Questions/results:

1. “Does the Supabase Android account flow compile with the existing locked dependencies?” Both APKs passed.
2. “Do cancellable authentication requests and the account-aware convoy changes compile and pass Android unit tests and lint?” Both APKs, unit tests and lint passed without warnings.
3. “Do the native account UI and session restoration fixtures compile after adding isolated credential storage tests?” Both APKs passed; subsequent native execution identified and corrected the UI fixture selector, then seven Auth checks passed.
4. “Do the corrected account interaction selector, Keystore fixture and refresh-aware sharing cleanup compile?” Both APKs passed.
5. “Do the final Supabase account and session changes pass Android unit tests and lint with strict dependency verification?” 43 JVM checks and lint passed without warnings.
6. “Do the final account recovery entry points pass APK builds, Android unit tests and lint?” Both APKs, all 43 JVM checks and lint passed without warnings; final native account UI checks also passed.

The managed workflow was finished successfully; its wrapper-owned logs were
removed by `finish`. No other build or owner logs were removed.

## Google integration and operating setup

The production release selects hosted Auth at
`https://ghddrkgouwtagsgjqcxy.supabase.co/auth/v1`, with the same issuer.
`project-drive-hosted-auth` supplies only `SUPABASE_PUBLISHABLE_KEY`; no service-role
key, signing key or Google client secret enters Go or the APK. The API runtime
includes trusted CA roots for verified upstream HTTPS. In Google mode password,
signup, recovery and refresh proxy routes return 404. The SDK owns those flows.
The config endpoint checks provider `/settings` and keeps Google disabled until
the provider actually enables it; provider failures fail closed. Go still checks
live `/user` identity on private operations and logout purges hub positions.

The unused homelab GoTrue Deployment/Service are removed from the active
Kustomization. Its database, credentials and optional source manifest are retained
for recovery and independent self-hosted regression fixtures, not silently deleted.

Android pins Supabase Kotlin Auth 3.9.0 (MIT), Ktor 3.6.0, Kotlin 2.4.21 and
Coroutines 1.11.0. The new dependencies require compile API 37.0; target API stays
36 and min API stays 26. SDK OAuth uses PKCE and the exact callback
`org.projectdrive.outset://auth/callback`. Keystore-encrypted atomic storage binds
sessions/verifiers to both project and API origin, with a ten-minute verifier
expiry. Google provider tokens are discarded; only Supabase sessions persist.
The browser opens after the verifier is durable, and cold-start callbacks are
handled even when Android restores Activity state. No callback tokens are logged.
The SDK's background auto-refresh is off; current UI/journey consumers own refresh
calls. Sign-out persists an encrypted revocation intent before local removal and
blocks another account flow until server sign-out completes.

Provider setup gate (outside Git): create the prepared Google Web OAuth client
with redirect `https://ghddrkgouwtagsgjqcxy.supabase.co/auth/v1/callback`; enter its
ID/secret directly in Supabase's Google provider panel and enable it. Add the exact
app callback to Supabase's redirect allowlist and use it as the native site URL;
keep nonce/email checks enabled and disable the unused Email provider. While the
Google consent project is in testing, configure the owner's test Google account.
Google credentials must not be sent in chat, committed or copied to application
Secrets. The browser's credential-entry handoff remains required. Real Google
sign-in and hosted account/convoy/revocation acceptance remain unverified.

## Google verification — 15 November 2026

Both APKs built; 44 JVM checks and lint passed. Seven distinct physical native
checks passed: three plain account UI checks, three SDK session/PKCE/offline
logout checks, and one isolated Keystore corruption check. The first UI run was
blocked by the phone's lock screen; four non-UI checks passed, and the three UI
checks passed after the owner unlocked it. No owner account was used by fixtures.
The final strict-build APK and test APK were installed in place and all seven native checks passed together (3.02 seconds). An in-place APK upgrade preserved all app table hashes/counts and all six saved
recordings; private pre/post snapshots passed SQLite integrity checks.
Backend Go vet and race/unit tests are recorded below with the final source.

Managed Google Gradle workflow `38bb5853e24e2c03567999063dc6e386` questions:

1. “Can the pinned Supabase Kotlin 3.9.0 auth client resolve with compatible Kotlin and locked Android dependencies?” Dependency resolution and lock/verification metadata generation passed.
2. “Do Google OAuth, encrypted SDK storage and the revised account fixtures compile into both Android APKs?” Initial AAR metadata check failed because two transitive dependencies require API 37.
3. “Do both OAuth APKs compile after matching the SDK dependencies' required Android 37 compile API?” Compilation reached the SDK's internal HTTP configuration opt-in; the SDK declaration was inspected and the narrow pinned-API opt-in added. An SDK XML tooling warning appeared on this initial platform build.
4. “Do both OAuth APKs compile with the pinned SDK transport configuration and callback guards?” Both APKs passed; four compatibility safe-call warnings were then corrected at the cited source lines.
5. “Do the Google sign-in APKs, Android unit tests and lint pass after resolving SDK compatibility warnings?” Both APKs, all 44 JVM checks and lint passed without warnings.
6. “Do the final Google OAuth APKs, 44 Android JVM checks and lint pass with strict dependency verification and cold-start callback handling?” Both APKs, all 44 JVM checks and lint passed with strict verification, without warnings.

`go vet ./...` and `go test -race ./...` passed for all Go packages; integration-only suites remain the CI disposable-PostGIS gate.
