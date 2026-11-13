# Place search and shared map locations

The owner explicitly added place-name search and map-link entry to the UI refresh.
This brings forward the named-destination part of Phase 1; the remaining Phase 0
field gates stay open. Neither endpoint is prefilled on a fresh app launch.

Choose **Navigation**, pick From or To, and search for a town, road or landmark.
Select a result to see its pin, or choose **Use my location** for the start. Hold
the map for a destination. Preview is enabled once both endpoints are selected.
Search and link selection never grant location permission or start guidance.

Paste a location link into the same field, use Android **Share → Project Drive**,
or open a supported `geo:` link with the app. Full OSM marker links and common
Google Maps query/destination/embedded-pin links are parsed without a map SDK or
API key. A viewport-only link is labelled as a map centre so it can be checked
before routing. Explicit destination data takes precedence over the viewport.
Unknown links and unavailable locations produce a visible message.

Short Google Maps and OSM links are expanded only after the user supplies one.
Expansion uses HTTPS, a small fixed map-host allowlist, at most five redirects,
no cookies or credentials, timeouts, and cancellable requests. No arbitrary URL
fetching occurs. A short link that does not expose a destination through redirects
needs a full link or place-name search. Android 12+ normally routes unverified
third-party HTTPS links to the browser: these are not domains we own or can verify.
Share-to-app and paste work without claiming those domains or changing defaults.

## Search data and deployment

The initial provider is a small read-only name gazetteer generated from the same
Geofabrik Kenya OSM snapshot as the map/router:

- Source SHA256: `6da91bb24ad192da15a0a25a481b0f8063da127e2e3fd0a1bc18e9d40394b6fa`.
- 72,023 named nodes/ways, including towns, roads, amenities and landmarks.
- JSON size: 10,551,219 bytes; SHA256:
  `90ce3f748ae2ee9810a7568a7bca6c09870034288a6dcc3fb61d55e569b3a86f`.
- Immutable homelab release: `kenya-search-90ce3f74` on the existing map PVC.
- Public ODbL download: `/maps/places-index`, with attribution, license URL and
  source hash inside the JSON. Application code keeps its separate AGPL license.

This is name search for the current Kenya snapshot, not a worldwide address or
house-number geocoder. Ranking favours exact names, then prefixes and settlements;
aliases and multiword queries are supported. Nearby duplicate name/type/locality
entries are collapsed in results. Inspect the selected pin, especially for a road
or a large area. Relations and some untranslated or missing OSM names are absent.
A full self-hosted geocoder can replace the `PlaceSearcher`/`PlaceSearch` boundaries
later. No public Nominatim/demo search endpoint is used.

API search is `POST /v1/navigation/search` with `{"query":"Nakuru"}`. It returns
up to ten places, region and attribution. It accepts 2–120 characters, limits the
body to 1 KiB, never logs the query or device location, and sets `Cache-Control:
no-store`. Four searches may execute concurrently, with bounded request lifetime.
The startup loader rejects malformed or oversized files. The index is read-only,
kept outside the image/database, and mounted into the API at `/map-data`. The API
memory limit increases to 256 MiB for its in-memory index. Trip and telemetry
credentials, authorization and retention are unchanged.

Build the data with pyosmium 4.3.1 (BSD-2-Clause, build-time only), which was verified
available and installed in a private workstation virtual environment:

```sh
python3 -m venv /path/to/osmium-tools
/path/to/osmium-tools/bin/pip install osmium==4.3.1
/path/to/osmium-tools/bin/python scripts/build-place-index.py \
  /path/to/map-data/sources/kenya-latest.osm.pbf \
  /path/to/map-data/search/kenya-places-v1.json
```

Use a new output path/release for updates; retain the source hash, license notice
and checksums. Do not overwrite a mounted release. The existing
`scripts/upload-map-data.sh` verifies a packaged release before atomic promotion;
the operator import Pod is temporary. API mounts are read-only and promotion
continues through the existing CI → GHCR → GitOps/Flux workflow. Revert the API
image **and its manifest/data-path configuration together** to recover a previous
version; a bare image rollback could leave a newer environment or mount in place.
No schema migration is added by this feature.

For local maps, generate the `search` directory beside `tiles` and `valhalla`, then
use the existing `infra/maps.compose.yaml` overlay and `MAP_DATA_DIR`. Ordinary API
startup without maps stays supported; search returns 503 when no index is configured.
The stationary navigation script now explicitly selects public fixture names through
search instead of relying on prefilled coordinates. Its server-disconnect checks
still require its documented loopback build and workstation forwarding setup.

## Verification

- Go vet/race, PostGIS trip/stream/telemetry integration, repeated migrations,
  vulnerability scan, Compose and Kustomize validation passed.
- Real-data index acceptance passed for Nakuru, Nairobi, Mombasa, Naivasha and
  Uhuru Park, alongside ranking, aliases, deduplication, validation, cancellation,
  concurrency and HTTP privacy/error contract checks.
- Android APK/test APK, 32 unit tests and lint pass. All three native input tests
  pass on the API 37 emulator: empty endpoints, latest search result ownership,
  explicit From/To selection, shared-origin parsing, ACTION_SEND delivery,
  unsupported-link handling, and no automatic GPS/guidance. The emulator-only
  isolated-model test also proves a late first GPS fix cannot replace an explicitly
  chosen start. No mock location provider is used.
- The final integrated emulator suite passes 14 tests (three camera, three input,
  four convoy, three telemetry, one public endpoint). The physical Samsung
  qualifies all 13 applicable checks across focused runs, including its final
  unlocked three-camera rerun. The synthetic-fix model fixture is emulator-only.
- Public HTTPS checks pass on both devices: search, dataset download, routing,
  tiles and fonts; private HTTP/WebSocket access remains 401 and operational
  endpoints remain externally hidden. A generic workstation HTTP client received
  an edge 403; the Android clients passed without relaxing edge policy.
- Search release `9c6d89b` passed CI and homelab deployment; the API loaded the
  immutable index, all four pods were Ready and internal health/readiness returned
  200. A snapshot showed API memory 23 MiB (not a peak measurement), within the
  256 MiB limit. Existing 5 GiB database and 10 GiB map PVCs remain Bound.
- The updated legacy loopback navigation script passes Python compilation but
  was not rerun end to end against its separate loopback build. Current HTTPS
  native tests and the manual named-place planner flow passed instead.

Reference formats: [Android deep links](https://developer.android.com/training/app-links/create-deeplinks),
[Maps URL parameters](https://developers.google.com/maps/documentation/urls/get-started),
[OSM browsing/sharing](https://wiki.openstreetmap.org/wiki/Browsing).
