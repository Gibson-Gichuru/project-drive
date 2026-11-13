#!/usr/bin/env python3
"""Opt-in emulator telemetry acceptance against disposable local Compose data.
Build debug/test APKs with loopback mapServerUrl and apiServerUrl properties first.
Synthetic GPS is injected only at the emulator ViewModel test boundary. No field
journey is simulated on a physical phone. Credentials remain on private stdin.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb'
PACKAGE = 'org.projectdrive.app'
COMPOSE = ['docker', 'compose', '-f', 'infra/compose.yaml', '--env-file', '.env']


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True, capture_output=True, timeout=90, **kwargs).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emulator', default='emulator-5554')
    args = parser.parse_args()
    if not args.emulator.startswith('emulator-'):
        parser.error('Synthetic telemetry acceptance requires an emulator')
    adb = [str(ADB), '-s', args.emulator]
    state = Path.home() / '.local/state/project-drive'
    state.mkdir(parents=True, exist_ok=True)
    jobs = []
    with tempfile.TemporaryDirectory(prefix='m5-emulator-', dir=state) as directory:
        private = Path(directory)
        env = dict(os.environ, APP_ENV='test')
        try:
            public = subprocess.check_output(['go', 'run', './cmd/dev-token', '-generate-key', str(private / 'seed')], cwd=ROOT / 'services/api', env=env)
            env['DEV_AUTH_PUBLIC_KEY'] = public.decode().strip()
            run(COMPOSE + ['up', '-d', '--no-deps', 'api'], env=env)
            for attempt in range(40):
                try:
                    urllib.request.urlopen('http://127.0.0.1:8080/readyz', timeout=2).close()
                    break
                except OSError:
                    time.sleep(.5)
            token = subprocess.check_output(['go', 'run', './cmd/dev-token', '-key', str(private / 'seed'), '-user', str(uuid.uuid4()), '-name', 'M5 emulator acceptance'], cwd=ROOT / 'services/api', env=env).decode().strip()
            def request(method, path, body=None, key=None):
                headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
                if key:
                    headers['Idempotency-Key'] = key
                req = urllib.request.Request('http://127.0.0.1:8080' + path, data=json.dumps(body).encode() if body is not None else (b'' if method == 'POST' else None), headers=headers, method=method)
                with urllib.request.urlopen(req, timeout=15) as response:
                    return json.load(response)
            trip = request('POST', '/v1/trips', {'name': 'M5 disposable emulator acceptance'}, str(uuid.uuid4()))
            request('POST', '/v1/trips/' + trip['id'] + '/start')
            for apk in ['debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk']:
                run(adb + ['install', '-r', '-t', ROOT / 'apps/android/app/build/outputs/apk' / apk])
            # Keep native GPS callbacks out of this deterministic synthetic test.
            for permission in ['ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION']:
                run(adb + ['shell', 'pm', 'revoke', PACKAGE, 'android.permission.' + permission])
            run(adb + ['reverse', 'tcp:18082', 'tcp:8080'])
            def instrument(test):
                return adb + ['shell', 'am', 'instrument', '-w', '-e', 'class', test, PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
            safety = run(instrument('org.projectdrive.app.TelemetrySafetyTest'))
            (private / 'safety.log').write_bytes(safety)
            if b'OK (3 tests)' not in safety:
                raise RuntimeError('Telemetry safety acceptance failed')
            print('PASS: Room v1 migration, durable bounded recovery/expiry/upload binding, native sensor start/stop', flush=True)
            fixture = json.dumps({'token': token, 'trip_id': trip['id']}).encode()
            run(adb + ['shell', f"run-as {PACKAGE} sh -c 'mkdir -p files && cat > files/telemetry-device-fixture.json'"], input=fixture)
            log = open(private / 'capture.log', 'wb')
            process = subprocess.Popen(instrument('org.projectdrive.app.TelemetryDeviceTest#captureSaveUploadAndArmProcessDeath'), stdout=log, stderr=subprocess.STDOUT)
            jobs.append((process, log))
            deadline = time.monotonic() + 80
            while time.monotonic() < deadline:
                try:
                    result = json.loads(run(adb + ['exec-out', 'run-as', PACKAGE, 'cat', 'files/telemetry-device-result.json']))
                    if result.get('stage') == 'kill-ready':
                        break
                except (subprocess.CalledProcessError, ValueError):
                    pass
                if process.poll() is not None:
                    raise RuntimeError('Capture instrumentation ended before process-death checkpoint')
                time.sleep(.5)
            else:
                raise RuntimeError('Capture acceptance timed out')
            print('PASS: synthetic GPS recording, rotation interruption, durable summary, native upload and private remote summary', flush=True)
            run(adb + ['shell', 'am', 'force-stop', PACKAGE])
            process.wait(timeout=15)
            recovery = run(instrument('org.projectdrive.app.TelemetryDeviceTest#recoverAfterRealProcessDeath'))
            (private / 'recovery.log').write_bytes(recovery)
            if b'OK (1 test)' not in recovery:
                raise RuntimeError('Process-death recovery acceptance failed')
            summaries = request('GET', '/v1/trips/' + trip['id'] + '/summary')
            if not summaries.get('recordings'):
                raise RuntimeError('Server did not retain uploaded aggregate')
            request('POST', '/v1/trips/' + trip['id'] + '/end')
            print('PASS: real Android process kill restores interrupted aggregate with recording off', flush=True)
        except Exception:
            import shutil
            diagnostic = state / ('m5-device-failure-' + time.strftime('%Y%m%d-%H%M%S'))
            diagnostic.mkdir(mode=0o700)
            for _, log in jobs:
                log.flush()
            for log in private.glob('*.log'):
                shutil.copy2(log, diagnostic / log.name)
            print('Private diagnostics: ' + str(diagnostic), flush=True)
            raise
        finally:
            for process, log in jobs:
                if process.poll() is None:
                    process.terminate()
                log.close()
            run(adb + ['shell', 'am', 'force-stop', PACKAGE])
            run(adb + ['shell', f'run-as {PACKAGE} rm -f files/telemetry-device-fixture.json files/telemetry-device-result.json files/telemetry-restart-armed no_backup/convoy-session.aes'])
            run(adb + ['reverse', '--remove', 'tcp:18082'])
            run(COMPOSE + ['up', '-d', '--no-deps', 'api'])


if __name__ == '__main__':
    main()
