# ADR 0003 — Supabase Auth with Google sign-in

Updated by owner direction, 15 November 2026. Supersedes the initial homelab
email/password and Postfix direction. Current evidence and outstanding gates:
[Phase 1 authentication](../milestones/P1-authentication.md).

Use hosted Supabase Auth on its Free plan, with Google as the account provider.
Use the Supabase Kotlin auth client for browser OAuth, PKCE exchange and session
refresh. Outset does not implement passwords or run an SMTP sender. Keep tokens
in Android Keystore encrypted storage outside backups; never log credentials or
callback codes. Signing in does not start sharing or upload local recordings.

Go/PostGIS remains Outset's data and authorization boundary. Verify provider
identity and live sessions before private operations, bound issuer/audience/role,
and enforce trip membership separately. Recheck WebSocket sessions and purge
queued positions on acknowledged logout. Account changes clear convoy state and
stop sharing. Development identities remain explicit local/test only.

The application remains based on open-source components. Google is the owner's
chosen external account provider and hosted Supabase adds a Free-plan dependency;
this is an explicit exception to the original fully self-hosted account direction.
Do not enable paid services. Preserve the self-hosted provider test fixture for
session/security regression coverage, with Mailpit used only as a non-relaying
local test catcher. Preserve existing Auth data during migration.

References: [Supabase Google Auth](https://supabase.com/docs/guides/auth/social-sign-in/auth-google),
[Supabase Kotlin auth](https://supabase.com/docs/reference/kotlin/auth-signinwithoauth),
[Supabase Kotlin source](https://github.com/supabase/supabase-kt/tree/3.9.0),
[Supabase Auth MIT license](https://github.com/supabase/auth/blob/v2.197.0/LICENSE).
