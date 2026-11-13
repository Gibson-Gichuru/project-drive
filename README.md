# Project Drive

Project Drive is an Android-first, open-source navigation and private convoy app. It is built around OpenStreetMap data, a provider-neutral navigation interface, and self-hosted services. No paid maps, hosted identity provider, proprietary location SDK, or metered API is required. Map rendering, regional OSM data, routing and geocoding are selected and evaluated in M1; the public OSM demo services are not a production dependency.

This repository implements Phase 0 milestone **M0 — Repository and environment**. See [the roadmap](docs/ROADMAP.md) for scope, acceptance gates and the next milestone.

## Local prerequisites

- Go 1.26.11 or newer (older Go installations automatically download the pinned toolchain)
- Docker Engine with Compose v2
- Android Studio's bundled JDK (JDK 17 or newer) and Android SDK platform 37
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

The debug APK is written to `apps/android/app/build/outputs/apk/debug/app-debug.apk`. M0 intentionally requests no network or location permission. Location collection and map UI are later milestones.

## Verification

```sh
make doctor  # report local toolchain availability
make ci      # Go vet/race tests, Go vulnerability scan, Compose/Kustomize validation, Android build and unit tests
```

The same `make ci` command can run on a clean workstation without a paid CI account. GitHub Actions mirrors these checks for the public repository. Workflow permissions are limited; pull-request CI uses disposable GitHub-hosted runners and has no deployment secrets.

## Homelab GitOps deployment

The homelab deployment uses the existing K3s/Flux setup, with an isolated Flux source in `project-drive-delivery` and app resources in `homelab-apps`. The API and its PostGIS database remain ClusterIP-only. PostgreSQL state uses a 5 GiB `local-path` PVC, so it is node-local and requires a backup plan before user data is stored. GHCR images are referenced by commit SHA; Flux reconciles the `homelab-deploy` branch after the release workflow builds and publishes the API image.

One-time operator setup after the public GitHub repository exists:

1. The deployment uses the repository-linked public GHCR image `ghcr.io/gibson-gichuru/project-drive`; no registry credentials or public ingress are required.
2. Create the Project Drive database Secret in `homelab-apps` with keys `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` and `DATABASE_URL`. Store the source file outside this repository with mode `0600`, use a unique random password, and remove the temporary file after creating the Secret. `DATABASE_URL` must point to `project-drive-db.homelab-apps.svc.cluster.local:5432` and URL-encode credentials.
3. Apply `deploy/homelab/rbac.yaml` using the authorized operator identity. It gives the Flux impersonated service account rights only over Deployments, StatefulSets and Services in `homelab-apps`.
4. Apply `deploy/homelab/flux/release.yaml` using the authorized operator identity. Flux then reads the public `homelab-deploy` branch and reconciles the app resources.
5. Enable the `Deploy to homelab` workflow for pushes to `main`. It builds an immutable image, publishes it to GHCR, and updates the deployment branch. Review the commit and Flux rollout status after first enabling it.

Use the repo's GitHub branch protection for `main`; do not execute untrusted pull-request code on a homelab runner. The deployment job uses GitHub-hosted runners only for image publishing, and Flux pulls the release from GitHub. No cluster credentials are stored in GitHub Actions. Do not expose the API publicly until authentication and privacy controls have been implemented and reviewed.

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
