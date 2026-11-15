# Outset

Outset (formerly Project Drive) is an Android-first, open-source navigation and private convoy app. It is built around OpenStreetMap data, a provider-neutral navigation interface, and self-hosted services. No paid maps, hosted identity provider, proprietary location SDK, or metered API is required. Map rendering, regional OSM data, routing and geocoding are selected and evaluated in M1; the public OSM demo services are not a production dependency.

Current work: [M6 validation and hardening](docs/milestones/M6.md). Software work
continues while remaining physical checks are grouped into [one field session](docs/consolidated-field-test.md).
Use [the desktop validation/demo runbook](docs/m6-desktop-validation.md) for the
repeatable PostGIS/WebSocket latency baseline and security/operations evidence.

The [journey report and car-connection update](docs/trip-reports-and-car-connection.md)
adds a dedicated report, bounded local speed charts for new recordings and advisory
Android Auto connection status. Existing trips retain their original data; no GPS
route history is stored and car connection never starts capture automatically.

**M0, M2 and M3 are complete. M4 — Android convoy is implemented, verified with one physical phone and two emulators, and deployed; its two-real-phone field gate is deferred. The owner reports normal navigation with no wrong turns on the Tigoni journey; M1 final arrival/road-access acceptance remains open.** The app now renders the self-hosted Kenya basemap and previews routes; the remaining real-world acceptance checks are recorded in the field review. See [the roadmap](docs/ROADMAP.md), [M1 status](docs/milestones/M1.md), and [navigation evaluation](docs/navigation-evaluation.md).

The Android UI uses the owner-selected reference direction: a full-screen pale map, charcoal floating controls and a compact journey card. Poppins is bundled locally. See [Outset design and verification](docs/outset-design.md). Existing package IDs, repository, API routes and local data remain compatible.

## Local prerequisites

- Go 1.26.11 or newer (older Go installations automatically download the pinned toolchain)
- Docker Engine with Compose v2
- Android Studio's bundled JDK (JDK 17 or newer) and Android SDK platform 36
- `make`, `kubectl` (only for validating the homelab manifests)

No cloud credentials are needed to build or run locally. The first Android build downloads open-source dependencies from Maven repositories.

## Start the API and PostGIS

```sh
make setup          # creates an ignored local .env from .env.example
make compose-config # check Compose configuration
make up             # builds API image and starts PostGIS, migrations and API
curl http://localhost:8080/healthz
curl http://localhost:8080/readyz
make logs
make down
```

PostgreSQL/PostGIS is published only on loopback (`127.0.0.1:5432`). The checked-in password is for local development only. Set a unique local value in `.env`; never reuse it in homelab or production. `make clean` deletes the local database volume.

## Android build

Set `JAVA_HOME` and `ANDROID_HOME` to the Android Studio bundled runtime and SDK if they are not already configured. For the default workstation installation:

```sh
export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_HOME="$HOME/Android/Sdk"
cd apps/android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is written to `apps/android/app/build/outputs/apk/debug/app-debug.apk`. With existing foreground location permission, the map starts at a fresh current fix. Without permission, “Use my location” explains and requests access. “Turn location off” persists across restarts. Idle map location stops in the background. Explicitly started guidance, recording and sharing continue through a visible journey service with notification Stop controls. See [background journeys](docs/background-journeys.md). Startup does not fill route endpoints. See [current-location behavior and checks](docs/current-location.md). Historical M0 startup/lifecycle evidence is recorded in [the device test](docs/device-tests/2026-11-13-M0.md).

## Verification

```sh
make doctor  # report local toolchain availability
make ci      # Go vet/race tests, Go vulnerability scan, Compose/Kustomize validation, Android build, unit tests and lint
```

The same `make ci` command can run on a clean workstation without a paid CI account. GitHub Actions mirrors these checks for the public repository. Workflow permissions are limited; pull-request CI uses disposable GitHub-hosted runners and has no deployment secrets.

## Homelab GitOps deployment

The homelab deployment uses the existing K3s/Flux setup, with an isolated Flux source in `project-drive-delivery` and app resources in `homelab-apps`. The API and its PostGIS database remain ClusterIP-only. PostgreSQL state uses a 5 GiB `local-path` PVC, so it is node-local and requires a backup plan before user data is stored. GHCR images are referenced by commit SHA; Flux reconciles the `homelab-deploy` branch after the release workflow builds and publishes the API image.

Homelab deployment status and access:

The deployment uses the repository-linked public GHCR image `ghcr.io/gibson-gichuru/project-drive`; no registry credentials are required. Flux reads the `homelab-deploy` branch and applies the release into `homelab-apps`. The PostGIS database Secret is provisioned on the cluster, outside Git. GitHub Actions builds an immutable image, publishes it to GHCR, and updates the GitOps branch after successful CI on `main`, including map-manifest updates. Manual dispatch can also promote a trusted `main` source. The workflow serializes releases and prevents an older tested source from replacing a newer release. No cluster credentials are stored in GitHub Actions.

To reach the API temporarily from an authorized workstation, run `kubectl -n homelab-apps port-forward service/project-drive-api 8080:8080`, then request `http://127.0.0.1:8080/healthz` or `/readyz`. The Service remains ClusterIP-only. The owner-authorized HTTPS tunnel exposes app paths separately; see [homelab access](docs/homelab-access.md).

Use the repo's GitHub branch protection for `main`; do not execute untrusted pull-request code on a homelab runner. The deployment job uses GitHub-hosted runners only for image publishing, and Flux pulls the release from GitHub. No cluster credentials are stored in GitHub Actions. Development identities remain disabled on the homelab, including through its public HTTPS hostname.

For the map spike, `deploy/homelab/maps.yaml` adds private Martin and Valhalla Services using a separate 10 GiB data PVC. The data import and immutable promotion procedure is in [the navigation runbook](docs/navigation-evaluation.md); archives and source extracts stay outside Git. The trial's gateway is disabled when `APP_ENV=production`.

Build local map data with `MAP_DATA_DIR` pointing outside the repository and `scripts/build-kenya-maps.sh`. Then start the map overlay:

```sh
MAP_DATA_DIR=/absolute/path/to/map-data docker compose -f infra/compose.yaml -f infra/maps.compose.yaml --env-file .env up -d --build
scripts/navigation-smoke.py http://127.0.0.1:8080
```

The default Android build uses `https://drive-api.ripples.boo` for maps and API traffic through the existing homelab tunnel. No workstation forwarding is needed. Private trip/telemetry operations remain unavailable there until production sign-in is implemented. For isolated local testing, explicitly build with `-PmapServerUrl=http://127.0.0.1:18081 -PapiServerUrl=http://127.0.0.1:18082` and configure device forwards. See [HTTPS setup, checks and recovery](docs/homelab-access.md).

Choose From and To through place search, a shared map link, your location or a map pin, then use “Preview route”; “Play preview” is explicitly simulated. Use “Use my location” and then “Start guidance” for a fresh foreground fix. No account is required for the developer spike. This is not a production navigation release.

The API health endpoints are `/healthz` (process liveness) and `/readyz` (database-aware readiness). The OpenAPI and WebSocket envelope contracts are under `contracts/`.

## Project structure

```text
apps/android/       Kotlin + Jetpack Compose app
services/api/       Go API, migrations and OCI image
contracts/          OpenAPI and WebSocket envelope schemas
deploy/homelab/     Kustomize app resources and Flux bootstrap/RBAC
infra/              local Docker Compose stack
docs/                specification, architecture, roadmap and ADRs
```

## License and data

Outset code is licensed under GNU AGPL-3.0-only (see `LICENSE`), which keeps network-served modifications open. Third-party dependencies retain their own licenses. OSM data is subject to the Open Database License and requires attribution. See the M1 decision record for selected map components, source-specific terms, update process and operational requirements before any map stack is deployed.

## M2 trip API

Trip lifecycle, private membership, hashed invitations and offline local/test identities are described in [the M2 report and local setup](docs/milestones/M2.md). The [REST contract](contracts/openapi.yaml) defines endpoints, retry behavior and limits. `make integration` now includes real PostGIS authorization and concurrent invite redemption tests. Homelab does not accept development identities; trip endpoints fail closed there. Android trip screens and the local device setup are described in [M4](docs/milestones/M4.md).

## M3 realtime developer API

The [M3 report](docs/milestones/M3.md) explains explicit sharing, authenticated WebSockets, single-instance bounds, revocation and reconnect semantics. `make integration` includes real WebSocket clients against PostGIS. Live coordinates remain ephemeral; Android convoy UI is implemented in [M4](docs/milestones/M4.md), and M1’s final acceptance remains separate from convoy validation.

## M5 local telemetry

Explicit foreground recording saves bounded derived summaries locally, without an account or automatic sharing/upload. The [M5 report](docs/milestones/M5.md) documents quality gates, candidate estimates, retention, private upload tests and physical journey evidence. The owner's 14 November journal drive exposed a candidate-detection defect; the [15 November correction](docs/device-tests/2026-11-15-journal-events.md) is installed; the [Tigoni data review](docs/device-tests/2026-11-15-tigoni.md) verifies normally completed recordings with retained acceleration/braking candidates. Calibration and full M6 checks remain open. See [the field checklist](docs/device-tests/2026-11-14-field.md).

## Map-first navigation UI

The app uses a full-screen map with on-demand Navigation, Convoy and Journal tools,
Poppins typography, day/night styling, free pan/zoom and Recenter. From and To start
empty; choose places by name, location permission, map pin or shared map link. See
[current Outset design](docs/outset-design.md), [earlier UI evidence](docs/ui-ux-refresh.md)
and [place search and links](docs/place-search.md).
