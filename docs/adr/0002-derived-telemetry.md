# ADR 0002 — Bounded on-device telemetry estimates

Date: 2026-11-13. Scope: Phase 0 M5, not calibrated driving analysis.

## Decision

Compute journey summaries on-device. Raw GPS fixes and raw IMU vectors are never
written to Room, the API or general logs. Retain only the last fix and a maximum
120-sample/two-second IMU ring in memory. Android LocationManager supplies native
GPS speed/accuracy/course; SensorManager captures available accelerometer,
rotation-vector and linear-acceleration callbacks at a target 20 Hz. A sensor can
be absent or stop reporting; GPS summaries remain available. Monotonic timestamps
anchor elapsed time and UTC is derived from the recording's initial wall clock.

Record only after a separate disclosure and explicit opt-in. Recording is local,
independent from navigation consent, convoy sharing and upload. It needs no
account/network. Leaving the foreground or rotation stops capture and marks an
interrupted summary; restart never resumes it. Keep the screen awake only during
an active recording. No background service or location permission was added.

Estimate distance from successive fresh fixes with reported accuracy at most
50 m. Exclude spatial jumps above 100 m/s and gaps over five seconds. Suppress
stationary jitter below half the combined accuracy (at least 3 m). Report GPS
coverage separately from total recording duration. Display sample-mean and maximum
reported speed only when speed accuracy is present, positive and at most 3 m/s.
These simple estimates can undercount slow travel or overcount residual GPS noise;
they are not map-matched odometer measurements or duration-weighted speed.

Candidate events use successive GPS speed/course deltas, **not calibrated phone
acceleration axes**. Require 1–3 second intervals, both position accuracies at
most 25 m, and speed accuracy at most 2 m/s. Acceleration/braking candidates start
at 3/3.5 m/s². Lateral candidates use speed multiplied by course-change rate,
require speeds at least 7 m/s and bearing accuracy at most 10 degrees, and start
at 4 m/s². Require two consecutive qualifying samples and a five-second cooldown
per type. Reject magnitudes above 30 m/s². Assign confidence 0.35 and explicitly
label all results uncalibrated candidates; do not score a driver. IMU streams
provide sensor-presence/quality context and a short-lived rotation-availability
hint, not gravity compensation or fused event classification. Calibration,
coordinate-frame alignment, filtering and threshold validation remain Phase 3.

## Bounds, persistence and upload

Limit each recording to four hours and 100 detailed candidate events. Retain
aggregate counts beyond that limit and report omitted details. Room checkpoints
derived data every five seconds, so abrupt process death can lose the last
checkpoint interval. Version 1 → 2 migration adds only the derived-recording table
and preserves the convoy revocation journal and selected trip. Recovery finalizes
active checkpoints as interrupted with capture off. Never use destructive schema
fallback.

Keep at most 20 local summaries for seven days; reject new recording when full
until the user deletes an old one. Clear detailed events after 24 hours, or after
an upload acknowledgment. Expiry is checked on startup/list operations and once
per minute while the telemetry ViewModel is alive; when the app is not running,
physical cleanup occurs on its next startup. Android backup remains disabled.
Raw ring contents are evicted after two seconds and released when capture stops.

Manual upload is separate and requires an authenticated active trip member and
an active or completed trip. Bind the final recording to trip+identity before
network transmission; uncertain retries cannot move it to another trip/account.
The recording UUID and canonical payload hash make an unchanged retry idempotent.
Send only summary/quality metadata and up to 100 derived events, maximum 64 KiB.
The API rejects unknown fields including raw coordinates, invalid/absent metadata,
non-finite values, inconsistent counts/times and unsupported source/calibration.
It derives user ownership from verified identity. A trip owner cannot read another
member's summaries. Limit 20 server recordings per user/trip and expire all
records/events seven days after upload; a minute-based worker deletes expired
rows, and reads exclude them immediately. Backups require their own retention;
no automatic deletion from historical backups is claimed.

The homelab rejects development identities and therefore offers no public private
trip/telemetry operation until production sign-in is implemented. Isolated local
Compose plus loopback-only debug credentials test upload and membership behavior.
Cloudflare is an optional owner-selected ingress, not a required product SDK or
metered API dependency. All capture, calculation, storage and transport libraries
are existing open-source dependencies.

## Consequences and validation

This supplies useful local summaries without retaining a raw journey or needing
network connectivity. It cannot produce a personal heatmap, validated unsafe-
driving conclusions, background recording or fused/calibrated telemetry. Real
journey accuracy and power consumption must be measured before those claims.

Pure estimator tests exercise jitter, jumps, quality, missing sensors, candidate
streaks/cooldowns, bearing wrap, gaps, buffer TTL and event bounds. Actual Room
migration/recovery and native sensor start/stop run on Android. Emulator acceptance
uses fixed public synthetic fixes at an instrumentation boundary and the real
local API; a controlled process kill verifies durable recovery. PostGIS tests
verify membership, ownership, bounds, idempotency and expiry cascade. Physical
field acceptance is recorded separately and cannot be inferred from these tests.
