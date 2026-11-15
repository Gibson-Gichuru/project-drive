# ADR 0003 — Self-hosted Supabase Auth

Decision accepted by the owner, 15 November 2026. Delivery and email gates are
tracked separately in [the Phase 1 milestone](../milestones/P1-authentication.md).

Use standalone Supabase Auth/GoTrue `v2.197.0`, pinned by OCI digest, in the
existing homelab. It owns email/password credentials and rotating sessions in a
separate PostgreSQL database with a non-superuser schema owner. Keep Go/PostGIS
as Outset's data and authorization boundary. No hosted Supabase subscription,
PostgREST, storage API or proprietary Android SDK is required.

Android uses a small Auth HTTP adapter through the existing HTTPS API. Credentials
are encrypted with Android Keystore AES-GCM, atomically stored outside backups,
and never placed in URLs or logs. Refresh is serialized; sign-out clears access
locally and retains only an encrypted revocation intent if offline. Account changes
stop publishing and clear visible convoy state. Sign-in never starts sharing.

The API bounds the user-facing Auth operations and omits provider diagnostics.
Its private `/user` call verifies the original bearer with the pinned Auth server,
including the provider's active session/user checks. Outset separately validates
issuer, audience, authenticated role, confirmed email, UUID subject/session and
bounded lifetime. The API does not receive the signing key. WebSockets recheck
sessions; acknowledged local logout also takes the hub delivery gate, purges
positions and invalidates queued updates before responding. Development identity
and Supabase modes are mutually exclusive.

Use an outbound-only Postfix sender (`boky/postfix:v5.2.1`, digest pinned) with
private source-restricted submission and DKIM. Sender DNS is isolated under
`outset-mail.ripples.boo`. SMTP/admin APIs stay private. Registration and recovery
remain off until delivery works; TLS/DKIM/SPF alone do not establish inbox delivery.
The ISP controls reverse DNS and the egress IP can change. Mailpit is only a
non-relaying disposable test fixture, never a production email substitute.

Supabase Auth is [MIT](https://github.com/supabase/auth/blob/v2.197.0/LICENSE).
The [Postfix image wrapper](https://github.com/bokysan/docker-postfix/blob/v5.2.1/LICENSE)
and [Mailpit](https://github.com/axllent/mailpit/blob/v1.33.1/LICENSE) are MIT;
Postfix itself is [dual EPL 2.0 / IPL 1.0](https://www.postfix.org/announcements/postfix-3.3.0.html).
Packaged DKIM/OS dependencies retain their own upstream notices. Running these
services on owned hardware adds no required paid API or hosted service dependency.

Primary behavior references: [Auth source](https://github.com/supabase/auth/tree/v2.197.0),
[configuration](https://supabase.com/docs/guides/self-hosting/auth/config),
[sessions](https://supabase.com/docs/guides/auth/sessions),
[Postfix wrapper configuration](https://github.com/bokysan/docker-postfix/tree/v5.2.1).
