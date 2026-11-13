# Project Drive

Project Drive is an Android-first, open-source navigation and private convoy app. It is built around OpenStreetMap data, a provider-neutral navigation interface, and self-hosted services. No paid maps, hosted identity provider, proprietary location SDK, or metered API is required. Map rendering, regional OSM data, routing and geocoding are selected and evaluated in M1; the public OSM demo services are not a production dependency.

**M0 is complete. M1 — Open mapping and navigation spike is in progress.** The app now renders the self-hosted Kenya basemap and previews routes; the real-world GPS route gate remains open. See [the roadmap](docs/ROADMAP.md), [M1 status](docs/milestones/M1.md), and [navigation evaluation](docs/navigation-evaluation.md).

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

The debug APK is written to `apps/android/app/build/outputs/apk/debug/app-debug.apk`. M1 requests Internet access and offers explicit foreground location permission. It collects location only after “Use my location,” stops collection in the background, and does not request background location or share live positions. Phone startup/lifecycle evidence for M0 is recorded in [the device test](docs/device-tests/2026-11-13-M0.md).

## Verification

```sh
make doctor  # report local toolchain availability
make ci      # Go vet/race tests, Go vulnerability scan, Compose/Kustomize validation, Android build, unit tests and lint
```

The same `make ci` command can run on a clean workstation without a paid CI account. GitHub Actions mirrors these checks for the public repository. Workflow permissions are limited; pull-request CI uses disposable GitHub-hosted runners and has no deployment secrets.

## Homelab GitOps deployment

The homelab deployment uses the existing K3s/Flux setup, with an isolated Flux source in `project-drive-delivery` and app resources in `homelab-apps`. The API and its PostGIS database remain ClusterIP-only. PostgreSQL state uses a 5 GiB `local-path` PVC, so it is node-local and requires a backup plan before user data is stored. GHCR images are referenced by commit SHA; Flux reconciles the `homelab-deploy` branch after the release workflow builds and publishes the API image.

Homelab deployment status and access:

The deployment uses the repository-linked public GHCR image `ghcr.io/gibson-gichuru/project-drive`; no registry credentials or public ingress are required. Flux reads the `homelab-deploy` branch and applies the release into `homelab-apps`. The PostGIS database Secret is provisioned on the cluster, outside Git. GitHub Actions builds an immutable image, publishes it to GHCR, and updates the GitOps branch after successful CI on `main`, including map-manifest updates. Manual dispatch can also promote a trusted `main` source. The workflow serializes releases and prevents an older tested source from replacing a newer release. No cluster credentials are stored in GitHub Actions.

To reach the API temporarily from an authorized workstation, run `kubectl -n homelab-apps port-forward service/project-drive-api 8080:8080`, then request `http://127.0.0.1:8080/healthz` or `/readyz`. The Service remains ClusterIP-only. Do not expose it publicly until authentication and privacy controls have been implemented and reviewed.

Use the repo's GitHub branch protection for `main`; do not execute untrusted pull-request code on a homelab runner. The deployment job uses GitHub-hosted runners only for image publishing, and Flux pulls the release from GitHub. No cluster credentials are stored in GitHub Actions. Do not expose the API publicly until authentication and privacy controls have been implemented and reviewed.

For the map spike, `deploy/homelab/maps.yaml` adds private Martin and Valhalla Services using a separate 10 GiB data PVC. The data import and immutable promotion procedure is in [the navigation runbook](docs/navigation-evaluation.md); archives and source extracts stay outside Git. The trial's gateway is disabled when `APP_ENV=production`.

Build local map data with `MAP_DATA_DIR` pointing outside the repository and `scripts/build-kenya-maps.sh`. Then start the map overlay:

```sh
MAP_DATA_DIR=/absolute/path/to/map-data docker compose -f infra/compose.yaml -f infra/maps.compose.yaml --env-file .env up -d --build
scripts/navigation-smoke.py http://127.0.0.1:8080
```

For a paired phone, select its current Wi-Fi ADB serial and run `adb -s SERIAL reverse tcp:18081 tcp:8080` for the local stack. For homelab testing, first port-forward the API to workstation port 18081, then use `adb -s SERIAL reverse tcp:18081 tcp:18081`. The default debug app uses `http://127.0.0.1:18081`; the HTTP exception exists only for loopback in the debug variant. An alternate self-hosted HTTPS endpoint can be supplied using `-PmapServerUrl=https://YOUR_HOST` at Android build time.

Use “Preview route” for fixed coordinates or a long-pressed map destination; “Play preview” is explicitly simulated. Use “Use my location” and then “Start guidance” for a fresh foreground fix. No account is required for the developer spike. This is not a production navigation release.

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

Project Drive code is licensed under GNU AGPL-3.0-only (see `LICENSE`), which keeps network-served modifications open. Third-party dependencies retain their own licenses. OSM data is subject to the Open Database License and requires attribution. See the M1 decision record for selected map components, source-specific terms, update process and operational requirements before any map stack is deployed.
