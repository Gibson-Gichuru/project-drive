#!/usr/bin/env python3
"""Build a small Kenya name gazetteer from the same OSM snapshot as the maps.

Requires pyosmium 4.3.1. Output is an ODbL derived database, not application code.
Never reads a user's location. Named nodes and ways only; not house-number geocoding.
"""
import argparse
import hashlib
import json
from pathlib import Path
import osmium

CATEGORIES = ('place', 'amenity', 'tourism', 'leisure', 'natural', 'shop', 'historic', 'railway', 'aeroway', 'highway')

class Places(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.places = []
        self.seen = set()

    def add(self, item, kind, lat, lon):
        name = item.tags.get('name') or item.tags.get('name:en')
        category = next((key for key in CATEGORIES if item.tags.get(key)), None)
        if not name or not category or len(name) > 200:
            return
        # Nearby fragments of the same named road should not flood search results.
        key = (name.casefold(), category, round(lat, 2), round(lon, 2))
        if key in self.seen:
            return
        self.seen.add(key)
        aliases = ';'.join(filter(None, (item.tags.get('name:en'), item.tags.get('name:sw'), item.tags.get('alt_name'), item.tags.get('short_name'))))
        locality = ', '.join(filter(None, (item.tags.get('addr:suburb'), item.tags.get('addr:city'))))
        self.places.append(dict(id=f'{kind}/{item.id}', name=name, kind=item.tags[category], locality=locality or 'Kenya', aliases=aliases[:400], latitude=round(lat, 7), longitude=round(lon, 7)))

    def node(self, node):
        if node.location.valid():
            self.add(node, 'node', node.location.lat, node.location.lon)

    def way(self, way):
        if not way.tags.get('name') or not any(way.tags.get(k) for k in CATEGORIES):
            return
        points = [(n.lat, n.lon) for n in way.nodes if n.location.valid()]
        if not points:
            return
        if way.tags.get('highway'):
            lat, lon = points[len(points)//2]
        else:
            if len(points)>1 and points[0]==points[-1]:
                points.pop()
            lat=sum(p[0] for p in points)/len(points)
            lon=sum(p[1] for p in points)/len(points)
        self.add(way, 'way', lat, lon)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args=parser.parse_args()
    if args.output.exists():
        parser.error('Use a new output file for each immutable data release')
    handler=Places()
    handler.apply_file(str(args.source), locations=True, idx='flex_mem')
    digest=hashlib.file_digest(args.source.open('rb'), 'sha256').hexdigest()
    dataset=dict(version=1, region='Kenya', attribution='© OpenStreetMap contributors', license='https://opendatacommons.org/licenses/odbl/1-0/', source_sha256=digest, places=sorted(handler.places, key=lambda p:p['id']))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dataset,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    print(json.dumps(dict(places=len(handler.places), bytes=args.output.stat().st_size, source_sha256=digest, output_sha256=hashlib.sha256(args.output.read_bytes()).hexdigest())))

if __name__=='__main__':
    main()
