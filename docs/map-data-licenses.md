# Map data and software notices

The Project Drive code license does not replace any dependency or data license. This inventory records the M1 sources; it is not the later public-release legal review.

| Component / source | License / notice | Use |
| --- | --- | --- |
| [OpenStreetMap](https://www.openstreetmap.org/copyright), [Geofabrik Kenya extract](https://download.geofabrik.de/africa/kenya.html) | ODbL 1.0; visible OpenStreetMap contributor attribution; preserve database licensing and applicable share-alike obligations when distributing source/derived databases | Regional road graph and vector data |
| [OpenMapTiles schema and design](https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md), [Planetiler profile](https://github.com/openmaptiles/planetiler-openmaptiles/blob/main/LICENSE.md) | BSD-3-Clause code; CC-BY-4.0 design. Display © OpenMapTiles alongside OSM attribution. This does not replace OSM data obligations | Generated basemap schema; the Project Drive style is original |
| [Natural Earth](https://www.naturalearthdata.com/about/terms-of-use/) | Public domain | Low-zoom context, obtained by Planetiler |
| [OSM water polygons](https://osmdata.openstreetmap.de/data/water-polygons.html), [lake centerlines](https://github.com/acalcutt/osm-lakelines) | OSM-derived auxiliary data; preserve attribution/data notices | Planetiler auxiliary inputs |
| [MapLibre Native](https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md) | BSD-2-Clause | Android renderer, version 13.7.1 |
| [Planetiler](https://github.com/onthegomap/planetiler/blob/main/LICENSE) | Apache-2.0 | Offline map build, version 0.10.2 |
| [Martin](https://github.com/maplibre/martin) | MIT OR Apache-2.0; upstream license files retained by upstream images | Tiles and glyphs, version 1.16.1 |
| [Valhalla](https://github.com/valhalla/valhalla/blob/3.9.1/COPYING) | MIT | Routing and instructions, version 3.9.1 |
| [Noto Sans Regular](https://github.com/notofonts/noto-fonts/blob/c971829a87e7920f960e7277c3dafd9bedd3c601/LICENSE) | SIL Open Font License 1.1; the complete license travels with the font in each data release | Latin labels for English/Kiswahili; no runtime font download |

Both the map renderer attribution control and a persistent Android credit show **© OpenStreetMap contributors · © OpenMapTiles**. The map source carries links to the attribution pages. Do not remove these when changing layout or packaging data. Keep the font license and this inventory in each packaged data release. Redistribution of the APK must also preserve dependency notices; MapLibre's license is bundled in the APK's `assets/licenses/`.

The source PBF and derived routing/vector archives stay outside the code repository and API image. Data packages contain `SHA256SUMS` and the source OSM SHA-256. Rebuild both basemap and routing from the same snapshot. Recheck all upstream terms and the OSM [tile](https://operations.osmfoundation.org/policies/tiles/) and [Nominatim](https://operations.osmfoundation.org/policies/nominatim/) service policies before changing sources. Neither public endpoint is used by this implementation.
