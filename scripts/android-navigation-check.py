#!/usr/bin/env python3
"""Stationary M1 checks on an authorized phone; chooses public place names explicitly through search.

Requires a loopback debug APK, the deployed Kenya place index, and working tcp:18081 reverse. Restarts the app,
leaves location collection off, restores rotation and the reverse on failure.
No screenshots, GPS coordinates, hierarchy dumps or device IDs are retained.
This is NOT a physical GPS journey or an offline-map-pack test.
"""
import argparse
import os
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
parser.add_argument('--adb', default=str(pathlib.Path.home()/'Android/Sdk/platform-tools/adb'))
args = parser.parse_args()
if not args.serial:
    parser.error('--serial or ANDROID_SERIAL is required to select the authorized device')
adb = [args.adb, '-s', args.serial]
package = 'org.projectdrive.app'
activity = package+'/.MainActivity'
remote = '/data/local/tmp/project-drive-m1-check-'+str(os.getpid())+'.xml'


def run(*commands):
    result = subprocess.run(adb+list(commands), capture_output=True, timeout=25)
    if result.returncode:
        raise RuntimeError('Android command failed; no device payload was printed')
    return result.stdout.decode(errors='replace')


def shell(*commands):
    return run('shell', *commands)


def nodes():
    try:
        shell('uiautomator', 'dump', remote)
        return list(ET.fromstring(shell('cat', remote)).iter('node'))
    finally:
        shell('rm', '-f', remote)


def wait_text(text, prefix=False, timeout=25):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        for node in nodes():
            value = node.get('text', '')
            if value == text or prefix and value.startswith(text):
                return node
        time.sleep(.3)
    raise AssertionError('Expected UI control/status not found: '+text)


def tap(text):
    node = wait_text(text)
    bounds = [int(x) for x in re.findall(r'\d+', node.get('bounds', ''))]
    if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        raise AssertionError('Control has no usable bounds: '+text)
    shell('input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))


def keeps_screen_on():
    raw = shell('dumpsys', 'window', 'windows')
    # Keep all other window data private; inspect only this activity's decoded flags.
    blocks = re.split(r'(?=\s+Window #\d+ Window\{)', raw)
    for block in blocks:
        if not re.search(r'Window #\d+ Window\{[^\n]*'+re.escape(package)+r'/', block):
            continue
        flags = re.search(r'\bfl=([0-9a-fA-F]+)\b', block)
        if 'KEEP_SCREEN_ON' in block or flags and int(flags[1], 16) & 0x80:
            return True
    return False


def check_screen_awake(expected):
    deadline = time.monotonic()+5
    while time.monotonic() < deadline:
        if keeps_screen_on() == expected:
            return
        time.sleep(.3)
    raise AssertionError('Guidance/playback screen-awake flag did not match expected state')


def launch():
    shell('am', 'start', '-W', '-n', activity)


def passed(label):
    print('PASS: '+label, flush=True)


rotation = shell('settings', 'get', 'system', 'user_rotation').strip()
automatic = shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
try:
    run('reverse', 'tcp:18081', 'tcp:18081')
    shell('am', 'force-stop', package)
    launch()
    wait_text('Location off', prefix=True)
    wait_text('© OpenStreetMap contributors', prefix=True)
    check_screen_awake(False)
    passed('fresh process defaults to location off; attribution visible; ordinary screen timeout retained')
    tap('Navigation')
    def select_named_place(placeholder, query):
        tap(placeholder)
        field = next(n for n in nodes() if n.get('class') == 'android.widget.EditText')
        bounds = [int(x) for x in re.findall(r'\d+', field.get('bounds', ''))]
        shell('input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
        shell('input', 'text', query.replace(' ', '%s'))
        shell('input', 'keyevent', 'KEYCODE_BACK')
        tap('Search places / open link')
        deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            matches=[n for n in nodes() if n.get('text')==query]
            if matches:
                tap(query)
                return
            shell('input','swipe','500','1900','500','1000','300')
            time.sleep(.3)
        raise AssertionError('Public place fixture missing from regional search')
    select_named_place('Choose a start', 'Nairobi')
    select_named_place('Where to?', 'Nairobi West')
    tap('Navigation')
    tap('Preview route')
    wait_text('Your route is ready')
    tap('Navigation')
    wait_text('Start guidance')
    passed('public sample route loads through self-hosted gateway')
    tap('Start guidance')
    wait_text('Use your location and wait', prefix=True)
    passed('GPS guidance refuses to start without a fresh accurate fix')
    tap('Play preview')
    wait_text('Simulated preview', prefix=True)
    wait_text('Stop')
    check_screen_awake(True)
    passed('playback explicitly labeled simulated; screen stays on')
    run('reverse', '--remove', 'tcp:18081')
    time.sleep(4)
    wait_text('Simulated preview', prefix=True)
    wait_text('Stop')
    passed('loaded route playback remains available without server; no live-GPS claim')
    tap('Stop')
    check_screen_awake(False)
    tap('Navigation')
    tap('Preview route')
    wait_text('Route unavailable.', prefix=True)
    passed('new route request fails clearly while server is disconnected')
    run('reverse', 'tcp:18081', 'tcp:18081')
    tap('Navigation')
    tap('Preview route')
    wait_text('Your route is ready')
    tap('Navigation')
    wait_text('Start guidance')
    passed('route calculation recovers when private connection returns')
    tap('Play preview')
    shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
    shell('settings', 'put', 'system', 'user_rotation', '1')
    wait_text('Simulated preview', prefix=True)
    check_screen_awake(True)
    shell('settings', 'put', 'system', 'user_rotation', '0')
    wait_text('Simulated preview', prefix=True)
    check_screen_awake(True)
    passed('rotation preserves loaded route/playback and awake state')
    shell('input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    launch()
    tap('Navigation')
    wait_text('Start guidance')
    check_screen_awake(False)
    passed('background/resume stops guidance/playback and restores normal screen timeout')
    shell('am', 'force-stop', package)
    launch()
    wait_text('Location off', prefix=True)
    check_screen_awake(False)
    passed('process restart does not resume location or guidance silently')
    raw = shell('dumpsys', 'meminfo', package)
    pss = re.search(r'TOTAL PSS:\s*(\d+)', raw)
    if pss:
        print('App memory snapshot: '+str(round(int(pss[1])/1024))+' MiB PSS (not a peak/battery benchmark)', flush=True)
    print('Stationary checks passed. Real GPS route-following and road observations remain required.', flush=True)
finally:
    run('reverse', 'tcp:18081', 'tcp:18081')
    for key, value in [('user_rotation', rotation), ('accelerometer_rotation', automatic)]:
        if value in {'0', '1', '2', '3'}:
            shell('settings', 'put', 'system', key, value)
        elif value == 'null':
            shell('settings', 'delete', 'system', key)
