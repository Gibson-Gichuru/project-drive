# Phase 0 validation plan

This is an acceptance record template, not a statement that tests have passed. M0/M2/M3 are complete. The owner explicitly deferred M1’s real journey and authorized M3; M3’s software gate has since passed; M4 software/device validation passed with one physical phone and two emulators; its two-real-phone field gate is deferred. M1 remains an open field-test checklist item. Existing evidence is in `docs/milestones/` and `docs/device-tests/`.

## Reproducible checks

- `make ci`: Go validation/race tests, PostGIS migration/idempotence/health, vulnerability scan, Compose/Kustomize validation, Android build, unit tests and lint. Run without paid credentials.
- Build the regional dataset as documented in `navigation-evaluation.md`, and run `scripts/navigation-smoke.py BASE_URL` against both local and homelab APIs.
- Physical Android: install over the explicitly selected paired Wi-Fi ADB transport; check attribution, map roads/labels, map-pin and coordinate selection, preview fit, simulated playback, rotation, background/resume, server disconnection/recovery, location off/denied/approximate/stale behavior and process restart. Preserve the user's rotation setting and permission preferences. Do not commit device identifiers, private addresses, pairing codes, screenshots of personal positions or GPS traces.

Run the stationary phone checker after installing a debug APK and configuring the loopback server forward:

```sh
scripts/android-navigation-check.py --serial "$ANDROID_SERIAL"
```

It uses only the fixed public sample and restarts the app with location off, so run it **before** preparing a personal journey. It checks actual window flags, cached-route playback, disconnection/recovery and lifecycle state; it restores rotation and the server reverse. It does not inject mock GPS, verify a real journey, or create an offline map pack.

## M1 field acceptance — deferred, still required

- [ ] Physical GPS journey, road/instruction/restriction review, arrival and final mapping ADR acceptance. The owner confirmed “not travelled yet” and authorized M3 on 2026-11-13.

1. While parked, choose a short, known drivable route in the target Kenyan area and verify the origin is on the intended surface road. Elevated roads, access restrictions and one-way routing need explicit review.
2. Establish the private server connection; verify a fresh location with suitable accuracy, then preview the route. Record data revision, device/OS, route names, network arrangement and start conditions, without recording a private position trail.
For the owner's phone-only session, load the route before leaving workstation Wi-Fi. Keep the app open: route instructions/geometry are held in memory, but uncached map areas and new route calculations may fail away from the workstation. Do not restart or change the route while disconnected. This arrangement tests a preloaded self-hosted route and its limitations, not full offline maps.

3. Have a passenger observe, or stop before interacting with the phone. Follow the route on the physical phone using actual GPS. Check turn instructions against roads/signs, current marker/freshness, progress, arrival, manual off-route handling and the ability to stop location.
4. In controlled stationary conditions, disconnect/reconnect the server. Record what remained cached, what failed and what recovered. Do not call this a full offline-maps test. Location and cached route behavior must remain clear when no fresh fix is available.
5. Record outcomes and incorrect/missing roads or instructions; fix or explicitly limit the trial. Accept ADR 0001 and close M1 only after this evidence passes. An on-device simulated preview does not satisfy the GPS journey gate.

Public ingress, production authentication, address search, automatic rerouting and full offline map downloads are outside this spike. Arrange authorized private connectivity for the actual field session; the current ADB reverse path depends on the workstation connection.

## Later gates

M2 domain/API authorization and invite/state-transition tests have passed. M3 actual WebSocket/PostGIS clients and revocation/reconnect/limits/TTL/restart tests have passed; see the milestone report. The owner authorized one real phone plus two emulators for M4 software validation and deferred the two-real-phone field gate. M4 still needs two physical phones and denied/approximate permissions, stale state, restart, disconnection and immediate share-off checks. M5 needs actual sensor/GPS trip summary and bounded-buffer/retention evidence. M6 needs reproducible field conditions, receipt-to-broadcast p95, battery measurements, privacy/security review and a demonstration script. These gates have not been executed by the M1 spike.

## M4 convoy acceptance

- [x] Android safety suite on the physical phone and two emulators; encrypted session/tamper checks and durable cleanup.
- [x] Three Android clients against the isolated local API; initially unauthorized third client denied; two emulators exchange fixed synthetic public positions, phone receives both.
- [x] Fresh/stale/expired markers, API outage/restart with reauthorization, explicit off, removal/end, denied-permission callback, rotation/background and actual process-kill cleanup.
- [x] Native trip create/invite/revoke/join/leave/start/end/cancel workflow; endpoint persistence/idempotency API tests; stationary M1 regression.
- [ ] Two real phones exchanging actual GPS on a field journey; user deferred this gate. Android approximate-permission field accuracy and real mobile connectivity still need observation. Emulated coordinates and a denied-permission callback are not physical GPS or complete OS permission-dialog field evidence.

See [M4 report](milestones/M4.md) for commands, limits and release evidence. Do not start M5 automatically while field gates are open; record any owner-authorized deferral explicitly.
