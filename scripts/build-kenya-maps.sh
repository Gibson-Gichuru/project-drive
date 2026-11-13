#!/usr/bin/env bash
set -euo pipefail
: "${MAP_DATA_DIR:?Set MAP_DATA_DIR to a new absolute directory outside the repository}"
[[ "$MAP_DATA_DIR" = /* ]] || { echo 'MAP_DATA_DIR must be absolute' >&2; exit 1; }
[[ ! -e "$MAP_DATA_DIR/tiles/kenya.mbtiles" && ! -e "$MAP_DATA_DIR/valhalla/valhalla_tiles.tar" ]] || { echo 'Output already exists; use a new directory for each data release.' >&2; exit 1; }
mkdir -p "$MAP_DATA_DIR/sources" "$MAP_DATA_DIR/tiles" "$MAP_DATA_DIR/valhalla"
curl -fLsS --retry 3 https://download.geofabrik.de/africa/kenya-latest.osm.pbf -o "$MAP_DATA_DIR/sources/kenya-latest.osm.pbf"
curl -fLsS https://download.geofabrik.de/africa/kenya-latest.osm.pbf.md5 -o "$MAP_DATA_DIR/sources/kenya-latest.osm.pbf.md5"
(cd "$MAP_DATA_DIR/sources" && md5sum -c kenya-latest.osm.pbf.md5)
# Both components consume the exact same regional snapshot.
cp "$MAP_DATA_DIR/sources/kenya-latest.osm.pbf" "$MAP_DATA_DIR/valhalla/kenya.osm.pbf"
docker run --rm --memory=4g --cpus=3 -e JAVA_TOOL_OPTIONS=-Xmx2g \
  -v "$MAP_DATA_DIR:/data" \
  ghcr.io/onthegomap/planetiler:0.10.2@sha256:cf32202dbc001a9ab4bc11534b642b13de3798179817da8558e567a3d13dd403 \
  --download --osm-path=/data/sources/kenya-latest.osm.pbf --output=/data/tiles/kenya.mbtiles --download-dir=/data/sources --threads=3
docker run --rm --memory=4g --cpus=3 -e server_threads=3 -e serve_tiles=False -e use_default_speeds_config=False \
  -v "$MAP_DATA_DIR/valhalla:/custom_files" \
  ghcr.io/valhalla/valhalla-scripted:3.9.1@sha256:6238809f9dc3057743d70a20efd3911ec38e458bfa13a0601909b58f68dfd956
# SIL Open Font License. Pin the upstream font content with its recorded source revision.
FONT_REV=c971829a87e7920f960e7277c3dafd9bedd3c601
curl -fLsS "https://raw.githubusercontent.com/notofonts/noto-fonts/$FONT_REV/hinted/ttf/NotoSans/NotoSans-Regular.ttf" -o "$MAP_DATA_DIR/tiles/NotoSans-Regular.ttf"
curl -fLsS "https://raw.githubusercontent.com/notofonts/noto-fonts/$FONT_REV/LICENSE" -o "$MAP_DATA_DIR/tiles/FONT-LICENSE.txt"
sha256sum "$MAP_DATA_DIR/sources/kenya-latest.osm.pbf"
echo 'Build complete. Keep the source snapshot and build logs outside Git.'
