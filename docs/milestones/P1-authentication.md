# Phase 1 — Supabase authentication

Status: implementation in progress, authorized by the owner on 15 November 2026.
The owner selected Supabase and requested homelab SMTP. Outstanding Phase 0 field
gates remain in the combined 25 November session.

## Scope and acceptance

1. Run the open-source Supabase Auth server in the homelab, with its own database
   and restricted database role. Preserve the existing application database.
2. Expose a bounded authentication API through the existing HTTPS origin. Keep
   admin APIs, signing keys, database and SMTP private. Development identities
   remain restricted to explicit local/test deployments.
3. Add email/password sign-in, encrypted rotating refresh sessions, restart
   recovery, sign-out, and account-specific convoy state. Keep anonymous maps and
   local recording available. Never resume location sharing after authentication.
4. Check verified Supabase users and active sessions on every private REST call;
   recheck active WebSocket sessions and discard revoked positions and queued data.
5. Configure a private SMTP sender, DKIM/SPF and delivery prerequisites. Enable
   verified registration/recovery only when actual email delivery is established.
6. Test synthetic accounts, cross-user reads/uploads, forged/expired/revoked
   credentials, refresh rotation, logout, transport failure and app restoration.
   Build, lint, CI, deploy and update the phone without erasing saved journeys.

## Integration decisions

Use standalone Supabase Auth (GoTrue), not a second application data API or
hosted Supabase subscription. The existing Go API remains the authorization
boundary. The pinned Auth implementation's `/user` endpoint validates signature,
audience, user ban state and existence of `session_id`; Outset also validates
issuer, role, subject and lifetime. No positive validation cache hides logout.
The Android client uses the documented Auth HTTP protocol through a small
adapter and the existing OkHttp transport. No service-role key is shipped.

Current primary references: [Supabase Auth](https://github.com/supabase/auth),
[self-hosting configuration](https://supabase.com/docs/guides/self-hosting/auth/config),
[sessions](https://supabase.com/docs/guides/auth/sessions).

## Verification so far

- Go vet/race suites pass; existing PostGIS trip, telemetry and real WebSocket
  acceptance pass. Existing migrations run twice without changing application rows.
- A fresh disposable stack, using a non-superuser Auth database role, passes real
  Supabase registration, refusal before confirmation, captured verification code,
  login, rotating refresh tokens, recovery/password replacement, third-user
  isolation, forged bearer refusal, own-only telemetry, positive two-account live
  synthetic position exchange, nonmember WebSocket refusal, and logout closing an
  upgraded WebSocket. The final fresh-stack run passed after all source changes.
- Seven native emulator checks pass: sign-in input and password clearing, gated
  email controls, explicit sign-out, single-flight refresh with eight consumers,
  restored identity, durable offline sign-out cleanup, current-token rejection,
  and actual Android Keystore encryption/corruption refusal (some checks share a
  test method). The first UI fixture targeted the inner label instead of the
  disabled button; the corrected button selector passes. No owner vault is used.
- Both APK builds, all 43 JVM tests and final lint pass with strict dependency verification and no warnings.
- Server-side homelab dry-run passes. Auth secrets and the dedicated database are
  provisioned. The SMTP fixture responds to EHLO/NOOP. Sender A/SPF/DKIM/DMARC
  records for `outset-mail.ripples.boo` resolve; the DKIM value exactly matches
  the stored public key. No real email has been sent.

Initial local Auth startup identified the required `auth` schema, upstream
maintenance-role grants and role search path. Those are now in the reproducible
bootstrap. TCP readiness avoids the Postgres image's temporary initialization
socket. The local non-relaying mail catcher uses unauthenticated private SMTP;
external deliveries require TLS. No hosted service or paid API was introduced.

## Operations and remaining gates

Use `python3 scripts/auth-acceptance.py` for disposable local acceptance. It
creates private ephemeral test keys, runs Auth/PostGIS plus non-relaying Mailpit,
and removes its containers and credential file on exit. It runs in CI too.
Do not point these synthetic tests at a production service.

Before first homelab reconciliation, an authorized operator runs the node logging
bootstrap (using the existing SSH/sudo aliases), then the database bootstrap:

```
python3 scripts/bootstrap-smtp-logging.py
python3 scripts/bootstrap-auth-homelab.py --kubeconfig /path/to/authorized-operator.yaml
```

This creates/reuses the Auth Secret, non-superuser database role, dedicated
`outset_auth` database/schema, DKIM Secret, private ingress policies and retained
1 GiB SMTP queue. It does not rotate existing credentials or touch application
rows. Ingress policies and queue live under `deploy/homelab/bootstrap`; the
existing restricted Flux reconciler needs no new privileges. The queue is not
pruned by Flux. Store the Auth database/signing Secrets and mail key in the
operator's existing private backup procedure; deleting them prevents recovery.
The application DB/map PVCs and development-identity environment guard remain.

Normal releases use the existing CI → immutable GHCR image → `homelab-deploy`
→ Flux path. Admin APIs and SMTP have no public route. The API forwards only
explicit sign-in/refresh/sign-out/registration/verification/recovery/password
operations, bounds input/output, suppresses provider errors, refuses redirects,
and returns no-store responses. A fixed 30 mutation/minute process budget
complements provider limits; this is a small private alpha, not an unrestricted
public sign-up service.

Email is initially disabled in the public API and signup disabled in Auth.
Enable both only after external delivery succeeds. The current egress IP has no
PTR record, which can cause recipient rejection; reverse DNS is controlled by the
ISP, not the `ripples.boo` DNS zone. The egress IP is not reserved by this work;
SPF/A records must be reviewed if it changes. The sender is outbound-only and has
no incoming mailbox/bounce processor. A verified PTR/delivery path and operational
mail handling are required before broader registration or beta claims.

The owner's physical phone is not currently discoverable. No installation or
new real-head-unit/field pass is claimed by this increment yet. External test
email recipient, CI/promotion, live acceptance and final phone update remain open.

### Final local regression checks

The final recovery-entry UI, both APKs, 43 JVM checks and lint pass. Eleven
separate native checks passed overall (seven Auth/Keystore plus four journey
regressions). A broad background-class invocation initially included a host-
termination fixture without its controller, disrupting subsequent background
checks. After resetting only the disposable emulator overlay, recording/rotation/
notification-stop, guidance/activity replacement and notification-denial checks
passed individually. The host then armed a saved checkpoint, intentionally killed
only the emulator app process, and the recovery check passed with recording off
and the interrupted summary recovered. The arming instrumentation's expected
process-crashed result is not counted as a test pass. No physical journey is
inferred from these emulator checks.

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

### Initial live delivery — 15 November 2026

Source `9cf76f68bb9fd276e5e80ee6a27f1c63fe69b5fc` passed
[CI 43722925729](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43722925729)
and [delivery 43723702736](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43723702736).
Flux applied `homelab-deploy@sha1:19733452b5d6b9cd26aa9d930a27747f76c3618b`;
all five deployments became Ready, including Auth and SMTP. DB/maps retained
the same PVC identities; the new 1 GiB mail queue is Bound. Public Auth config,
nonexistent-account refusal, email gate, private REST refusal and hidden admin/
operational routes passed. Five fixed routes, vector tile and glyph checks passed.
No production account or external email was created by these probes.

A node's existing enforcing rsyslog AppArmor profile attached inside the mail
container and refused the generated `/tmp/rsyslog.conf`. Merely choosing another
container profile did not avoid that host attachment; the disposable profile and
probe were removed. `bootstrap-smtp-logging.py` now adds only the pinned image's
config/anonymizer/interpreter/stdout paths through the existing local include on
all three nodes. The host profile remains enforcing; no kernel setting, global
profile disable, RBAC or workload capability change was made. Original local
includes are preserved under `/var/backups/outset-mail-profile-20261115` on each
node. To undo, remove only the owned include line and `local/rsyslog-outset`, then
reload the existing profile; preserve subsequent unrelated local changes.
A synthetic syslog address was masked by the running anonymizer. No email was
sent. A follow-up adds an SMTP startup probe for the DKIM worker's cold startup.

Private pre-release preservation material is at
`~/.local/state/project-drive/backups/auth-pre-release-20261115T141137Z` (0700/0600):
application/Auth PostgreSQL archives, roles, three exact Secrets and storage
metadata. Both `pg_restore --list` catalog checks passed. A restore was not run.
