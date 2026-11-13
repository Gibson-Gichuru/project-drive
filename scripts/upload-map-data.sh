#!/usr/bin/env bash
set -euo pipefail
: "${KUBECONFIG:?Select an authorized kubeconfig explicitly}"
: "${MAP_RELEASE_DIR:?Set MAP_RELEASE_DIR to the packaged directory}"
: "${MAP_RELEASE_ID:?Set MAP_RELEASE_ID, matching the manifests subPath}"
[[ "$MAP_RELEASE_ID" =~ ^[a-z0-9][a-z0-9-]+$ ]] || { echo 'Invalid release identifier' >&2; exit 1; }
(cd "$MAP_RELEASE_DIR" && sha256sum -c SHA256SUMS)
kubectl -n homelab-apps wait --for=condition=Ready pod/project-drive-map-import --timeout=120s
kubectl -n homelab-apps exec project-drive-map-import -- sh -ec 'test ! -e "/data/releases/$1"; mkdir -p /data/releases; mkdir "/data/releases/$1.importing"' sh "$MAP_RELEASE_ID"
tar -C "$MAP_RELEASE_DIR" -cf - . | kubectl -n homelab-apps exec -i project-drive-map-import -- tar -xf - -C "/data/releases/$MAP_RELEASE_ID.importing"
kubectl -n homelab-apps exec project-drive-map-import -- sh -ec 'cd "/data/releases/$1.importing"; sha256sum -c SHA256SUMS; cd ..; mv "$1.importing" "$1"' sh "$MAP_RELEASE_ID"
echo 'Verified release uploaded. Deploy matching subPath through GitOps, then remove only the import Pod. Keep the PVC.'
