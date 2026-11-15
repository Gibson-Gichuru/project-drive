# Phase 1 — Supabase authentication

## Current direction — 15 November 2026

The owner selected Google sign-in through hosted Supabase Auth and canceled the
homelab SMTP sender. Supabase owns account creation, Google OAuth, token exchange
and refresh. The Android integration is being moved to the Supabase Kotlin auth
client. Go/PostGIS remains the application and convoy authorization boundary.
Google sign-in must never enable location sharing or uploads automatically.

A separate Outset Free project is being prepared; the existing connected project
contains another application's data and is not being repurposed. Google provider
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
The four task-owned DNS records under outset-mail.ripples.boo also need removal.
Live retirement verification is recorded below as it completes.

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

