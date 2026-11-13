# Phase 0 validation plan

This is an acceptance record template, not a statement that tests have passed. Run gates sequentially; the active gate is M1. Existing evidence is in `docs/milestones/` and `docs/device-tests/`.

## Reproducible checks

- `make ci`: Go validation/race tests, PostGIS migration/idempotence/health, vulnerability scan, Compose/Kustomize validation, Android build, unit tests and lint. Run without paid credentials.
- Build the regional dataset as documented in `navigation-evaluation.md`, and run `scripts/navigation-smoke.py BASE_URL` against both local and homelab APIs.
- Physical Android: install over the explicitly selected paired Wi-Fi ADB transport; check attribution, map roads/labels, map-pin and coordinate selection, preview fit, simulated playback, rotation, background/resume, server disconnection/recovery, location off/denied/approximate/stale behavior and process restart. Preserve the user's rotation setting and permission preferences. Do not commit device identifiers, private addresses, pairing codes, screenshots of personal positions or GPS traces.

Run the stationary phone checker after installing a debug APK and configuring the loopback server forward:

```sh
scripts/android-navigation-check.py --serial "$ANDROID_SERIAL"
```

It uses only the fixed public sample and restarts the app with location off, so run it **before** preparing a personal journey. It checks actual window flags, cached-route playback, disconnection/recovery and lifecycle state; it restores rotation and the server reverse. It does not inject mock GPS, verify a real journey, or create an offline map pack.

## M1 field acceptance — still required

1. While parked, choose a short, known drivable route in the target Kenyan area and verify the origin is on the intended surface road. Elevated roads, access restrictions and one-way routing need explicit review.
2. Establish the private server connection; verify a fresh location with suitable accuracy, then preview the route. Record data revision, device/OS, route names, network arrangement and start conditions, without recording a private position trail.
For the owner's phone-only session, load the route before leaving workstation Wi-Fi. Keep the app open: route instructions/geometry are held in memory, but uncached map areas and new route calculations may fail away from the workstation. Do not restart or change the route while disconnected. This arrangement tests a preloaded self-hosted route and its limitations, not full offline maps.

3. Have a passenger observe, or stop before interacting with the phone. Follow the route on the physical phone using actual GPS. Check turn instructions against roads/signs, current marker/freshness, progress, arrival, manual off-route handling and the ability to stop location.
4. In controlled stationary conditions, disconnect/reconnect the server. Record what remained cached, what failed and what recovered. Do not call this a full offline-maps test. Location and cached route behavior must remain clear when no fresh fix is available.
5. Record outcomes and incorrect/missing roads or instructions; fix or explicitly limit the trial. Accept ADR 0001 and close M1 only after this evidence passes. An on-device simulated preview does not satisfy the GPS journey gate.

Public ingress, production authentication, address search, automatic rerouting and full offline map downloads are outside this spike. Arrange authorized private connectivity for the actual field session; the current ADB reverse path depends on the workstation connection.

## Later gates

M2 adds domain/API authorization and invite/state-transition tests. M3 adds concurrent WebSocket clients and revocation/reconnect/limits tests. M4 needs two physical phones and denied/approximate permissions, stale state, restart, disconnection and immediate share-off checks. M5 needs actual sensor/GPS trip summary and bounded-buffer/retention evidence. M6 needs reproducible field conditions, receipt-to-broadcast p95, battery measurements, privacy/security review and a demonstration script. These gates have not been executed by the M1 spike.
