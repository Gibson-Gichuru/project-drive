# One combined field session

Owner decision, 15 November 2026: build and verify software between milestones;
collect field-dependent acceptance checks into one coordinated outing. Do not ask
for a separate drive for every feature. Keep unexecuted checks open, and retain
already valid evidence rather than repeating it without a reason.

## Readiness before scheduling

- Prepare the final candidate APK and record source, device/OS and map-data revision.
  Update in place while no journey is active; preserve existing journals/preferences.
- Two real Android phones must be available for the convoy and two-device battery
  checks. One phone plus emulators remains valid software evidence, not that gate.
- Both phones need working legitimate authentication for public convoy testing.
  The current public backend deliberately disables development identities and the
  app now supports hosted Supabase Google sign-in; its first physical login and
  restart restoration passed. Finish hosted convoy and sign-out acceptance before
  the combined convoy outing; do not publish development authentication
  or assume the OSM browser login signs into Outset.
- Use the public HTTPS map/API route over mobile data, without workstation ADB
  reverses. Verify both clients' map/search/route loading while parked. Full offline
  packs, traffic-aware ETAs, voice guidance and automatic rerouting are not claimed.
- Choose a familiar permitted route with a public destination and a safe stopping
  point. Include a known one-way/access or surface/elevated-road case only when it
  naturally fits the route. Record public names, not a private start or GPS trail.
- Mount devices consistently, note brightness and battery settings, and leave both
  unplugged. Choose a 40–50 minute recording window on both phones, inside the
  30–60 minute target. Start/end parked checks can be part of that window.

If these prerequisites are missing, continue software work and leave the outing
unscheduled. A repeat is needed only for a failed check or a material software/data
change that invalidates its evidence, not for every subsequent milestone.

## Run sheet

Only a passenger operates moving-phone controls. Perform network, permission,
sharing and lifecycle manipulations while parked. Drive normally; never provoke
hard braking, rapid acceleration or cornering to obtain candidate counts.

| Part | One-session actions | Evidence / acceptance |
| --- | --- | --- |
| Parked start | Check attribution, named-place/shared-link destination, actual current origin, fresh usable GPS, preview fit, pan/zoom and Recenter. Start guidance, separately consent to journal recording and convoy sharing. Check ongoing notification. | Correct real location and route; no fixed default origin; permission/notification state visible; both real participants shown with freshness/accuracy. |
| Ordinary journey | Follow the route. Observe instruction/marker progression and permitted road approach. Note any missed/incorrect instruction, restriction, road gap or UI failure. | M1 road/route observation and explicit arrival result at the destination. An estimate is not proof of legal access. |
| Community road reports | While parked, sign in and explicitly enable nearby reports. Publish only an actual observed hazard using a checked map pin; a second signed-in participant can confirm/dismiss. During ordinary guidance, a passenger observes the nearest passive warning and notes parallel/elevated-road false positives. Pause network access while parked and check that old reports disappear within 90 s. Withdraw the observation only if it was erroneous or is no longer relevant. | No automatic location query on sign-in; no duplicate-vote inflation; no passed/expired/disputed/camera guidance warning; navigation gestures and destination remain intact. No fabricated public hazard or extra drive is needed for testing. |
| Background/display | Record at least ten minutes screen-off on one device and at least five minutes in another app; stagger checks on the second device. Check guidance/recording/sharing after return. | Physical duration evidence and continued collection, with correct stale states if fixes stop. A mere `interrupted=false` summary is not proof of screen-off survival. |
| Parked Android Auto | If a head unit is available, connect wired/wirelessly with the phone app open, check the connection badge, disconnect and verify status. Repeat return-to-app after a screen-off interval during a manually started journey. | Actual projection detection and disconnect recovery; connecting alone never starts recording/sharing/guidance. No projected Outset dashboard is claimed. Head-unit checks stay deferred if hardware is unavailable; do not schedule another drive only for detection. |
| Parked connectivity | Briefly remove network access on one participant, verify the offline/retry message and hidden local convoy markers, then restore access and verify a fresh snapshot. On the still-connected phone, verify the offline peer ages to stale (about 10 s) and disappears at TTL (about 30 s). Stop sharing during a second brief outage and restore access; it must remain off. Check a loaded route versus an uncached request. | Recovery without falsely live markers or silently resurrected consent; a stop during backoff cancels retries and finishes pending cleanup after reconnection. Document cached-only limits. Do not confuse offline display with an offline map pack. |
| Parked privacy/permission | Revoke sharing on one phone; verify location disappears on the other. If practical, exercise permission denial/approximate mode on the second phone and restore its chosen setting before continuing. Use a separate disposable trip to test member removal/end without disrupting the main journal. | Share-off/removal/end deny subsequent locations. Denied/approximate fixes remain clearly labelled. Never weaken public authentication for this test. |
| Arrival and finish | Explicitly note whether arrival appeared. Finish journals and stop guidance/sharing; inspect notification Stop behavior while safely parked if still active. Open View trip report and inspect new speed history, gap/quality state and candidate counts. Confirm no active mode remains. | Both summaries saved with counts/quality and interruption state; controls stop collection. Chart contains derived estimates only, no route trail. Existing owner records remain intact; legacy trips correctly lack a reconstructed chart. |
| Private review | Read summaries promptly, before detailed events expire at 24 h (summaries expire at 7 days). Review durations/coverage, GPS/sensor counts, candidate/detail consistency, background/screen-off, charging, battery and network aggregates. | One private evidence bundle supports M1/M4/M5/M6 decisions. Keep raw database copies, identifiers, private timestamps and personal metrics out of Git. Publish only a redacted acceptance report. |

Candidate counts are uncalibrated GPS-derived estimates. Zero candidates during a
smooth drive can be correct; counts alone cannot establish sensitivity or false
positives. No raw speed/path history is saved for reconstruction. Existing 15 November
[Tigoni evidence](device-tests/2026-11-15-tigoni.md) already proves physical journal
capture and longitudinal candidate persistence; do not repeat a special event drive.

## Result form

| Item | Result to fill after the session |
| --- | --- |
| Candidate source / data release / devices | Pending |
| Public route names / ordinary conditions | Pending |
| Guidance progression / arrival / road-access observations | Pending |
| Two real participants / stale / reconnect / revocation | Pending |
| Background and screen-off on both phones | Pending |
| Android Auto real connection/disconnect (if head unit available) | Pending; simulated UI/provider checks are separate |
| Two 30–60 minute recordings / battery and charging qualification | Pending |
| Crashes, failures or changes requiring a targeted repeat | Pending |
| Gate decisions and explicit remaining limits | Pending |

Server receipt-to-write latency is a separate automated measurement. Run it before
the outing; a GPS callback gap, phone-to-phone stopwatch or HTTP round trip does not
measure it. See [M6 desktop validation](m6-desktop-validation.md).
