#!/usr/bin/env python3
"""Opt-in three-device local acceptance; no credentials or positions are printed.
Requires one connected phone, two booted emulators, built debug + androidTest APKs,
and the local Compose PostGIS stack. Stops test apps and restores auth-disabled API.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb'
PACKAGE = 'org.projectdrive.app'
COMPOSE = ['docker', 'compose', '-f', 'infra/compose.yaml', '--env-file', '.env']


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True, capture_output=True, **kwargs).stdout


def adb(serial, *args, **kwargs):
    return run([ADB, '-s', serial, *args], **kwargs)


def write_private(serial, name, data):
    # A fixed shell program receives bytes on stdin. No secret is a shell argument.
    assert name in {'convoy-device-fixture.json', 'convoy-device-command.json', 'convoy-workflow-fixture.json'}
    adb(serial, 'shell', f"run-as {PACKAGE} sh -c 'mkdir -p files && cat > files/{name}'", input=json.dumps(data).encode())


def stage(serial):
    try:
        return json.loads(adb(serial, 'exec-out', 'run-as', PACKAGE, 'cat', 'files/convoy-device-result.json'))['stage']
    except (subprocess.CalledProcessError, ValueError, KeyError):
        return ''


def wait_stage(serial, wanted, timeout=55):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if stage(serial) == wanted:
            return
        time.sleep(.5)
    raise RuntimeError(f'Device did not reach {wanted}; inspect private instrumentation report')


def command(serial, name, wanted=None, timeout=55):
    write_private(serial, 'convoy-device-command.json', {'command': name})
    if wanted:
        wait_stage(serial, wanted, timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phone', required=True)
    parser.add_argument('--evidence-dir', type=Path, help='Optional screenshots of this fixed public synthetic test only')
    parser.add_argument('--second', default='emulator-5554')
    parser.add_argument('--third', default='emulator-5556')
    args = parser.parse_args()
    devices = [args.phone, args.second, args.third]
    assert len(set(devices)) == 3
    for emulator in devices[1:]:
        assert emulator.startswith('emulator-'), 'Synthetic publisher must be an emulator'
    state_dir = Path.home() / '.local/state/project-drive'
    state_dir.mkdir(parents=True, exist_ok=True)
    jobs = []
    env = dict(os.environ, APP_ENV='test')
    with tempfile.TemporaryDirectory(prefix='m4-devices-', dir=state_dir) as directory:
        private = Path(directory)
        try:
            public = subprocess.check_output(['go', 'run', './cmd/dev-token', '-generate-key', str(private / 'seed')], cwd=ROOT / 'services/api', env=env)
            env['DEV_AUTH_PUBLIC_KEY'] = public.decode().strip()
            run(COMPOSE + ['up', '-d', '--no-deps', 'api'], env=env)
            def request(index, method, path, body=None, key=None):
                headers = {'Authorization': 'Bearer ' + tokens[index]}
                if body is not None:
                    headers['Content-Type'] = 'application/json'
                if key:
                    headers['Idempotency-Key'] = key
                request = urllib.request.Request('http://127.0.0.1:8080' + path, data=json.dumps(body).encode() if body is not None else (b'' if method == 'POST' else None), headers=headers, method=method)
                with urllib.request.urlopen(request, timeout=15) as response:
                    return json.load(response)
            for _ in range(40):
                try:
                    urllib.request.urlopen('http://127.0.0.1:8080/readyz', timeout=2).close()
                    break
                except (OSError, urllib.error.URLError):
                    time.sleep(.5)
            ids = [str(uuid.uuid4()) for _ in devices]
            tokens = [subprocess.check_output(['go', 'run', './cmd/dev-token', '-key', str(private / 'seed'), '-user', user, '-name', f'M4 test client {index + 1}'], cwd=ROOT / 'services/api', env=env).decode().strip() for index, user in enumerate(ids)]
            trip = request(0, 'POST', '/v1/trips', {'name': 'M4 controlled device acceptance', 'origin': {'latitude': -1.2864, 'longitude': 36.82}, 'destination': {'latitude': -1.3152, 'longitude': 36.8148}}, str(uuid.uuid4()))
            path = '/v1/trips/' + trip['id']
            invitation = request(0, 'POST', path + '/invites', {'expires_in_s': 900, 'max_uses': 10}, str(uuid.uuid4()))
            request(1, 'POST', '/v1/trips/join', {'token': invitation['token']})
            request(0, 'POST', path + '/start')
            for device in devices:
                adb(device, 'install', '-r', str(ROOT / 'apps/android/app/build/outputs/apk/debug/app-debug.apk'))
                adb(device, 'install', '-r', '-t', str(ROOT / 'apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
                adb(device, 'reverse', 'tcp:18082', 'tcp:8080')
                adb(device, 'reverse', 'tcp:18081', 'tcp:18081')
                adb(device, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
            def launch(index, role):
                fixture = {'token': tokens[index], 'trip_id': trip['id'], 'role': role, 'peer_id': ids[2] if index == 1 else ids[1], 'point_index': index}
                write_private(devices[index], 'convoy-device-fixture.json', fixture)
                report = open(private / f'client-{index}-{role}.log', 'wb')
                process = subprocess.Popen([str(ADB), '-s', devices[index], 'shell', 'am', 'instrument', '-w', '-e', 'class', 'org.projectdrive.app.ConvoyDeviceTest#controlledConvoySession', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=report, stderr=subprocess.STDOUT)
                jobs.append((process, report))
                return process
            outsider = launch(2, 'outsider')
            outsider.wait(timeout=45)
            wait_stage(devices[2], 'unauthorized-denied')
            print('PASS: third Android client denied private read and sharing without membership', flush=True)
            request(2, 'POST', '/v1/trips/join', {'token': invitation['token']})
            launch(0, 'observer'); launch(1, 'publisher'); launch(2, 'publisher')
            wait_stage(devices[1], 'connected'); wait_stage(devices[2], 'connected'); wait_stage(devices[0], 'peer-visible')
            command(devices[1], 'assert-reconnected', 'reconnected')
            command(devices[2], 'assert-reconnected', 'reconnected')
            if args.evidence_dir:
                # Parse only the fixed tab control; never print or retain the UI tree.
                ui = '/data/local/tmp/convoy-check-ui.xml'
                try:
                    adb(devices[0], 'shell', 'uiautomator', 'dump', ui)
                    tree = ET.fromstring(adb(devices[0], 'exec-out', 'cat', ui))
                    node = next(n for n in tree.iter('node') if n.get('text') == 'Convoy')
                    bounds = [int(x) for x in re.findall(r'\d+', node.get('bounds'))]
                    adb(devices[0], 'shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    time.sleep(1)
                    args.evidence_dir.mkdir(parents=True, exist_ok=True)
                    (args.evidence_dir / 'convoy-recent.png').write_bytes(adb(devices[0], 'exec-out', 'screencap', '-p'))
                finally:
                    adb(devices[0], 'shell', 'rm', '-f', ui)
            print('PASS: phone sees two emulator participants; emulators see each other over native Android WebSockets', flush=True)
            command(devices[1], 'pause-updates', 'updates-paused')
            time.sleep(11)
            command(devices[0], 'assert-stale', 'stale-confirmed')
            if args.evidence_dir:
                (args.evidence_dir / 'convoy-stale.png').write_bytes(adb(devices[0], 'exec-out', 'screencap', '-p'))
            command(devices[0], 'assert-expired', 'expiry-confirmed', 40)
            print('PASS: native Android marker ages to stale and expires without new updates', flush=True)
            command(devices[1], 'resume-updates', 'updates-resumed')
            command(devices[0], 'assert-reconnected', 'reconnected')
            # An actual API stop must produce a disconnected state, then a fresh
            # authorized snapshot after restart. The separate map server remains up.
            run(COMPOSE + ['stop', 'api'], env=env)
            command(devices[0], 'assert-disconnected', 'disconnected-confirmed', 30)
            run(COMPOSE + ['up', '-d', '--no-deps', 'api'], env=env)
            command(devices[0], 'assert-reconnected', 'reconnected', 55)
            print('PASS: confirmed API disconnect/restart recovers through authorized fresh snapshot', flush=True)
            command(devices[1], 'playback-guard', 'playback-blocked')
            time.sleep(2)
            command(devices[1], 'stop', 'sharing-stopped')
            command(devices[0], 'assert-expired', 'expiry-confirmed')
            print('PASS: explicit share-off revokes publication and removes remote marker', flush=True)
            command(devices[1], 'restart-sharing', 'connected-again')
            command(devices[1], 'permission-denied', 'permission-stopped')
            print('PASS: denied-permission callback stops native sharing', flush=True)
            command(devices[1], 'restart-sharing', 'connected-again')
            request(0, 'DELETE', path + '/members/' + ids[1])
            command(devices[1], 'assert-removed', 'removal-confirmed')
            print('PASS: owner removal clears and stops the removed Android client', flush=True)
            command(devices[0], 'background', 'background-safe')
            command(devices[0], 'restart-sharing', 'connected-again')
            command(devices[0], 'rotate', 'rotation-safe')
            print('PASS: background/rotation never silently restore sharing', flush=True)
            command(devices[0], 'prepare-restart', 'restart-armed')
            adb(devices[0], 'shell', 'am', 'force-stop', PACKAGE)
            recovery = run([ADB, '-s', devices[0], 'shell', 'am', 'instrument', '-w', '-e', 'class', 'org.projectdrive.app.ConvoyDeviceTest#processRestartRevokesWithoutResuming', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'])
            if b'OK (1 test)' not in recovery:
                raise RuntimeError('Process restart acceptance failed')
            wait_stage(devices[0], 'restart-safe')
            print('PASS: actual process death leaves sharing off and drains durable revocation journal', flush=True)
            request(0, 'POST', path + '/end')
            command(devices[2], 'assert-removed', 'removal-confirmed')
            for device in devices[1:]:
                command(device, 'finish', 'finished')
            for process, _ in jobs:
                process.wait(timeout=30)
            for _, report in jobs:
                report.flush()
            for log in private.glob('client-*.log'):
                # The observer is deliberately killed for the process-death test.
                if 'observer' not in log.name and 'OK (1 test)' not in log.read_text(errors='replace'):
                    raise RuntimeError('Instrumentation did not finish successfully')
            print('PASS: trip end closes remaining Android streams', flush=True)
            write_private(devices[2], 'convoy-workflow-fixture.json', {'owner_token': tokens[0], 'member_token': tokens[1]})
            workflow = run([ADB, '-s', devices[2], 'shell', 'am', 'instrument', '-w', '-e', 'class', 'org.projectdrive.app.TripWorkflowTest', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'])
            if b'OK (1 test)' not in workflow:
                (private / 'client-native-workflow.log').write_bytes(workflow)
                raise RuntimeError('Native trip workflow acceptance failed')
            print('PASS: native create/endpoints, invite retry/revoke, join/leave/rejoin, start/end/cancel workflow', flush=True)
        except Exception:
            diagnostic = state_dir / ('m4-device-failure-' + time.strftime('%Y%m%d-%H%M%S'))
            diagnostic.mkdir(mode=0o700)
            import shutil
            for log in private.glob('client-*.log'):
                shutil.copy2(log, diagnostic / log.name)
            print('Private instrumentation diagnostics saved at ' + str(diagnostic), flush=True)
            raise
        finally:
            if 'trip' in locals() and 'tokens' in locals():
                try:
                    request(0, 'POST', '/v1/trips/' + trip['id'] + '/cancel')
                except (OSError, urllib.error.HTTPError):
                    pass
            for process, report in jobs:
                if process.poll() is None:
                    process.terminate()
                report.close()
            for device in devices:
                try:
                    adb(device, 'shell', 'am', 'force-stop', PACKAGE)
                    adb(device, 'shell', f'run-as {PACKAGE} rm -f files/convoy-device-fixture.json files/convoy-device-command.json files/convoy-device-result.json files/convoy-workflow-fixture.json files/convoy-restart-armed no_backup/convoy-session.aes')
                    adb(device, 'reverse', '--remove', 'tcp:18082')
                except subprocess.CalledProcessError:
                    pass
            run(COMPOSE + ['up', '-d', '--no-deps', 'api'])


if __name__ == '__main__':
    main()
