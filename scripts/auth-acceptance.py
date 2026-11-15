#!/usr/bin/env python3
"""Run real Auth acceptance with ephemeral databases and non-relaying test mail."""
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.request


def main():
    root = Path(__file__).resolve().parents[1]
    env = dict(os.environ, SUPABASE_ACCEPTANCE_URL='http://127.0.0.1:18080', MAILPIT_ACCEPTANCE_URL='http://127.0.0.1:18025')
    with tempfile.TemporaryDirectory(prefix='outset-auth-ci-') as private:
        path = Path(private) / 'auth.env'
        path.write_text('AUTH_DB_PASSWORD=' + secrets.token_hex(32) + '\nAUTH_JWT_SECRET=' + secrets.token_hex(48) + '\n')
        path.chmod(0o600)
        command = ['docker', 'compose', '-p', 'outset-auth-ci', '-f', str(root / 'infra/auth.compose.yaml'), '--env-file', str(path)]
        try:
            result = subprocess.run(command + ['up', '-d', '--build'], cwd=root, capture_output=True, text=True)
            if result.returncode:
                print('Disposable Auth stack could not start; no credential output displayed')
                raise SystemExit(1)
            for address in ['http://127.0.0.1:19999/health', 'http://127.0.0.1:18080/readyz']:
                end = time.monotonic() + 60
                while True:
                    try:
                        with urllib.request.urlopen(address, timeout=3) as response:
                            if response.status == 200:
                                break
                    except OSError:
                        pass
                    if time.monotonic() >= end:
                        raise RuntimeError('Disposable Auth readiness deadline exceeded')
                    time.sleep(.5)
            result = subprocess.run(['go', 'test', '-race', '-count=1', '-v', './internal/httpapi', '-run', '^TestSupabaseEndToEnd$'], cwd=root / 'services/api', env=env, capture_output=True, text=True)
            # The test emits only operations/statuses and controlled result text.
            print((result.stdout + result.stderr)[-3000:])
            if result.returncode:
                raise SystemExit(result.returncode)
        finally:
            subprocess.run(command + ['down', '--remove-orphans'], cwd=root, capture_output=True, check=True)


if __name__ == '__main__':
    main()
