# Homelab HTTPS access

On 2026-11-13 the owner authorized publishing the prototype backend through the
existing Cloudflare `home-lab` tunnel. The dashboard confirmed route creation and
created the proxied CNAME. This is an optional ingress arrangement on the owner's
existing account/domain; the application and map services remain self-hostable
without Cloudflare. No paid product, plan upgrade or new subscription was added.

| Setting | Value |
| --- | --- |
| App origin | `https://drive-api.ripples.boo` |
| Tunnel | `home-lab` |
| Hostname | `drive-api.ripples.boo` |
| Path expression | `^/(maps/.*\|v1/.*)$` |
| Origin service | `http://project-drive-api.homelab-apps.svc.cluster.local:8080` |
| DNS | Proxied CNAME to the existing tunnel subdomain |

The seven existing published applications were preserved. The API, PostGIS,
Martin and Valhalla Services remain internal ClusterIP resources. No NodePort,
router port forward or public database listener was added. Only `/maps/*` and
`/v1/*` reach the API through this hostname. `/healthz`, `/readyz`, `/metrics` and
`/` fall through to the tunnel's 404 response. Kubernetes probes continue using
the private Service/pod listener. Do not add a wildcard path for this origin.

Navigation is an unauthenticated Phase 0 prototype with fixed regional map data,
bounded tile/font paths and bounded routing inputs/deadlines. Publishing it makes
those operations publicly reachable; this is not production authentication or a
public-release security review. Trip, membership, sharing, WebSocket and telemetry
operations continue enforcing their existing authorization boundary. The homelab
runs `APP_ENV=homelab`, without a development verification key: private operations
fail closed with 401 until the planned self-hosted production sign-in is available.
Never enable local/test identity on this public deployment.

The Cloudflare proxy terminates public HTTPS and forwards through the existing
encrypted tunnel to the private HTTP Service. It can process origin/destination
requests and viewport requests; never send raw phone GPS to diagnostic scripts.
No interactive Access login was added to this API hostname, since the native app
needs direct requests. Native TLS certificate validation stays enabled.

## Android builds

The default debug and release configuration uses the HTTPS app origin for both
maps/routing and the private API. Installing that APK no longer requires an ADB
reverse or workstation port forward for network access. Local telemetry recording
also works without an account. Convoy and telemetry upload still require sign-in;
public reachability does not make development identities valid on the homelab.

Override both origins for isolated development/instrumentation tests:

```sh
cd apps/android
./gradlew --no-daemon \
  -PmapServerUrl=http://127.0.0.1:18081 \
  -PapiServerUrl=http://127.0.0.1:18082 \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Only debug loopback has an Android cleartext exception. Build properties require
HTTPS for non-loopback servers and reject credentials, paths, query strings and
fragments. Local test-session import is enabled only in debug builds targeting a
loopback API. Release builds always disable it; public-host builds cannot load a
previously saved development bearer. Use an alternative HTTPS origin with
`-PmapServerUrl=https://YOUR_HOST -PapiServerUrl=https://YOUR_HOST` when self-hosting.

## Checks and recovery

Run `scripts/navigation-smoke.py https://drive-api.ripples.boo` to exercise only
fixed public Kenya examples, one vector tile and one font range. The script uses
an application User-Agent; the account's existing edge policy rejects the default
Python urllib agent with error 1010. No bot-protection setting was disabled.

For an installed default debug app and test APK, native opt-in checks use Android
HttpURLConnection for routes/assets and OkHttp for private REST/WebSocket denial:

```sh
adb -s "$ANDROID_SERIAL" reverse --remove tcp:18081
adb -s "$ANDROID_SERIAL" reverse --remove tcp:18082
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e publicEndpointChecks true -e class org.projectdrive.app.PublicEndpointTest \
  org.projectdrive.app.test/androidx.test.runner.AndroidJUnitRunner
```

The removal commands can report an absent listener if no forward exists; leave unrelated forwards intact.
This test reads no device location. A 401 WebSocket handshake proves the request
reaches the authenticated endpoint, not successful authorized convoy operation.
A real phone journey, mobile-network transition and authenticated public convoy
remain separate gates.

Recovery: remove only the `drive-api.ripples.boo` published application and its
CNAME in the existing tunnel/zone. Preserve all other routes, the tunnel token,
replicas and private services. Rebuild the app with a replacement HTTPS origin or
explicit loopback development configuration. This external account route is
managed separately from Flux; API releases continue through the established
GitHub CI → immutable GHCR image → GitOps branch → Flux workflow. No account or
tunnel credentials belong in Git. Cloudflare's dashboard routing behavior is
specified in [the official routing documentation](https://developers.cloudflare.com/tunnel/concepts/routing/).

Verified on 2026-11-13: five public Kenya route cases, vector tile and font probes
passed. Native opt-in HTTPS acceptance passed independently on the physical
Samsung Android 17/API 37 phone and API 37 emulator with Project Drive reverses
removed. Public trip creation and WebSocket requests were denied with 401;
operational paths returned 404. Mobile-data switching and authenticated public
convoy/upload have not been tested and are not claimed.

After M5 deployment, the same public route/tile/font/auth-denial checks passed again against the promoted image. Both existing tunnel replicas and all four Project Drive services were Ready; database and map PVC bindings were unchanged.
