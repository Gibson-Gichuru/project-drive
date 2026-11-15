#!/usr/bin/env python3
"""Provision only Outset's new Auth database and private credentials.

Uses the already-authorized operator identity. Values stay in memory/stdin;
never print manifests, SQL, passwords or JWT keys.
Does not modify application rows or expose any Service.
"""
import argparse
import base64
import json
from pathlib import Path
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--kubeconfig', required=True)
    args = parser.parse_args()
    prefix = ['kubectl', '--kubeconfig', args.kubeconfig, '-n', 'homelab-apps']

    def command(parts, data=None):
        result = subprocess.run(prefix + parts, input=data, capture_output=True)
        if result.returncode:
            raise RuntimeError('Authorized bootstrap operation failed; credentials and SQL omitted')
        return result.stdout

    def read_secret(name):
        value = command(['get', 'secret', name, '--ignore-not-found', '-o', 'json'])
        return json.loads(value) if value.strip() else None

    auth = read_secret('project-drive-auth')
    if auth is None:
        password = secrets.token_hex(32)
        signing = secrets.token_hex(48)
        auth = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'project-drive-auth'}, 'type': 'Opaque',
                'stringData': {'DATABASE_URL': 'postgres://outset_auth:' + password + '@project-drive-db:5432/outset_auth?sslmode=disable',
                               'JWT_SECRET': signing}}
        command(['create', '-f', '-'], json.dumps(auth).encode())
    else:
        from urllib.parse import urlparse
        password = urlparse(base64.b64decode(auth['data']['DATABASE_URL']).decode()).password
        if not password or len(password) != 64 or any(c not in '0123456789abcdef' for c in password):
            raise RuntimeError('Existing Auth secret needs an explicit reviewed bootstrap; not rotating it')

    # Only constant identifiers and a generated hexadecimal literal enter SQL.
    sql = r"""DO $$ BEGIN
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='postgres') THEN
  CREATE ROLE postgres NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
 END IF;
 IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='outset_auth') THEN
  CREATE ROLE outset_auth LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
 END IF;
END $$;
ALTER ROLE outset_auth SET search_path = auth, pg_catalog;
SELECT 'CREATE DATABASE outset_auth OWNER outset_auth' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='outset_auth')\gexec
""" % password
    command(['exec', '-i', 'statefulset/project-drive-db', '--', 'sh', '-ec',
             'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], sql.encode())
    setup = b"REVOKE ALL ON DATABASE outset_auth FROM PUBLIC; CREATE SCHEMA IF NOT EXISTS auth AUTHORIZATION outset_auth; REVOKE ALL ON SCHEMA public FROM PUBLIC;"
    command(['exec', '-i', 'statefulset/project-drive-db', '--', 'sh', '-ec',
             'exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d outset_auth'], setup)
    print('Dedicated Auth database/schema provisioned; application data preserved')

    support = Path(__file__).resolve().parents[1] / 'deploy/homelab/bootstrap/auth-support.yaml'
    command(['apply', '--server-side', '-f', str(support)])
    print('Private Auth ingress policy provisioned; reconciler RBAC unchanged')


if __name__ == '__main__':
    main()
