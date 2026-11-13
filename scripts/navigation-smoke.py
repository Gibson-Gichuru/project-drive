#!/usr/bin/env python3
"""Exercises only fixed public sample points. Never prints phone GPS coordinates."""
import json, math, pathlib, sys, urllib.error, urllib.request
base = sys.argv[1].rstrip('/') if len(sys.argv) > 1 else 'http://127.0.0.1:8080'
root = pathlib.Path(__file__).resolve().parents[1]
for sample in json.loads((root/'tests/navigation/kenya-routes.json').read_text()):
    request = urllib.request.Request(base+'/v1/navigation/route',data=json.dumps({k:sample[k] for k in ('origin','destination')}).encode(),headers={'Content-Type':'application/json','User-Agent':'ProjectDrive-Smoke/0.1'})
    with urllib.request.urlopen(request,timeout=25) as response:
        assert response.status == 200
        route=json.load(response)
    assert len(route['geometry']) >= 2 and route['distance_m'] > 0 and route['duration_s'] > 0
    assert len(route['maneuvers']) >= 2
    assert all(0 <= turn['start_index'] <= turn['end_index'] < len(route['geometry']) for turn in route['maneuvers'])
    print(f"{sample['name']}: {route['distance_m']/1000:.1f} km; {route['duration_s']/60:.0f} min; {len(route['maneuvers'])} instructions")
# Nairobi viewport, not a user's position.
z=12; lat=-1.2864; lon=36.8172
x=int((lon+180)/360*(1<<z)); y=int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*(1<<z))
for path in (f'/maps/tiles/{z}/{x}/{y}', '/maps/fonts/Noto%20Sans%20Regular/0-255'):
    with urllib.request.urlopen(urllib.request.Request(base+path,headers={'User-Agent':'ProjectDrive-Smoke/0.1'}),timeout=15) as response:
        assert response.status == 200 and len(response.read())>0
print('Nairobi vector tile and Latin glyph range: passed')
