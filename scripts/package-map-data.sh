#!/usr/bin/env bash
set -euo pipefail
: "${MAP_DATA_DIR:?Set MAP_DATA_DIR to the completed build directory}"
: "${MAP_RELEASE_DIR:?Set MAP_RELEASE_DIR to a new absolute directory}"
[[ "$MAP_RELEASE_DIR" = /* && ! -e "$MAP_RELEASE_DIR" ]] || { echo 'Release directory must be absolute and must not exist.' >&2; exit 1; }
mkdir -p "$MAP_RELEASE_DIR/tiles" "$MAP_RELEASE_DIR/valhalla/valhalla_tiles"
cp "$MAP_DATA_DIR/tiles/kenya.mbtiles" "$MAP_DATA_DIR/tiles/NotoSans-Regular.ttf" "$MAP_DATA_DIR/tiles/FONT-LICENSE.txt" "$MAP_RELEASE_DIR/tiles/"
for file in valhalla.json valhalla_tiles.tar admins.sqlite timezones.sqlite; do
  cp "$MAP_DATA_DIR/valhalla/$file" "$MAP_RELEASE_DIR/valhalla/"
done
python3 - "$MAP_RELEASE_DIR/tiles/kenya.mbtiles" <<'PY'
import sqlite3,sys
con=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True)
assert con.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
assert con.execute('select count(*) from tiles').fetchone()[0]>0
print('MBTiles integrity and tile count passed')
PY
cp docs/map-data-licenses.md "$MAP_RELEASE_DIR/DATA-LICENSES.md"
(cd "$MAP_RELEASE_DIR" && find tiles valhalla -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
sha256sum "$MAP_DATA_DIR/sources/kenya-latest.osm.pbf" | awk '{print $1}' > "$MAP_RELEASE_DIR/OSM-SOURCE-SHA256"
chmod -R a+rX "$MAP_RELEASE_DIR"
echo 'Release packaged. Verify SHA256SUMS before promotion.'
