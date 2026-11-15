#!/usr/bin/env python3
"""Prove subdomain DKIM signing through pinned Postfix/Rspamd into Mailpit.

Only synthetic reserved-domain messages enter an externally isolated Docker
network. The private fixture key is temporary. No real email is sent.
"""
import base64
from email import policy
from email.parser import BytesParser
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile

CAPTURE_PROGRAM = r'''
from email.message import EmailMessage
from email.utils import formatdate
import smtplib, time, urllib.request, sys
end = time.monotonic() + 180
while True:
    try:
        with smtplib.SMTP('127.0.0.1', 587, timeout=2) as smtp:
            assert smtp.noop()[0] == 250
        break
    except (OSError, smtplib.SMTPException):
        if time.monotonic() >= end:
            raise RuntimeError('Disposable SMTP readiness deadline exceeded')
        time.sleep(.5)
message = EmailMessage()
message['From'] = 'Outset <noreply@outset-mail.example.invalid>'
message['To'] = 'capture@example.invalid'
message['Subject'] = 'Synthetic subdomain signing acceptance'
message['Date'] = formatdate(usegmt=True)
message.set_content('Synthetic SMTP signing acceptance.\n')
with smtplib.SMTP('127.0.0.1', 587, timeout=10) as smtp:
    assert not smtp.send_message(message)
end = time.monotonic() + 30
while True:
    try:
        with urllib.request.urlopen('http://capture:8025/api/v1/message/latest/raw', timeout=2) as response:
            raw = response.read(65537)
        assert len(raw) <= 65536
        sys.stdout.buffer.write(raw)
        break
    except OSError:
        if time.monotonic() >= end:
            raise RuntimeError('No signed message reached isolated capture')
        time.sleep(.5)
'''


def main():
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='outset-smtp-ci-') as directory:
        key = Path(directory) / 'outset-mail.example.invalid.private'
        result = subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048'], capture_output=True, check=True)
        key.write_bytes(result.stdout)
        key.chmod(0o600)
        env = dict(os.environ, SMTP_SIGNING_KEYS=directory)
        compose = ['docker', 'compose', '-p', 'outset-smtp-ci', '-f', str(root / 'infra/smtp.compose.yaml')]
        try:
            result = subprocess.run(compose + ['up', '-d'], env=env, capture_output=True)
            if result.returncode:
                raise RuntimeError('Disposable SMTP stack did not start; output withheld')
            # No published ports: execute inside the external-network-isolated fixture.
            result = subprocess.run(compose + ['exec', '-T', 'smtp', 'python3', '-c', CAPTURE_PROGRAM], env=env, capture_output=True, timeout=230)
            if result.returncode:
                raise RuntimeError('Isolated SMTP/capture acceptance failed; no external message sent')
            raw = result.stdout
            parsed = BytesParser(policy=policy.default).parsebytes(raw)
            signature = str(parsed['DKIM-Signature'] or '')
            tags = dict(part.strip().split('=', 1) for part in signature.split(';') if '=' in part)
            assert tags.get('d') == 'outset-mail.example.invalid', 'Subdomain signature missing or normalized to parent domain'
            assert tags.get('s') == 'mail' and tags.get('a') == 'rsa-sha256'
            assert len(re.sub(r'\s+', '', tags.get('b', ''))) > 100, 'Empty signing result'
            assert tags.get('c') == 'relaxed/relaxed'
            normalized = raw.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
            body = normalized.split(b'\r\n\r\n', 1)[1]
            lines = [re.sub(br'[ \t]+', b' ', line).rstrip(b' \t') for line in body.split(b'\r\n')]
            canonical = b'\r\n'.join(lines).rstrip(b'\r\n') + b'\r\n'
            assert re.sub(r'\s+', '', tags['bh']) == base64.b64encode(hashlib.sha256(canonical).digest()).decode(), 'Signed body hash does not match capture'
            print('Pinned Postfix/Rspamd signed the exact sender subdomain with selector mail; captured body hash matches. No external email sent.')
        finally:
            subprocess.run(compose + ['down', '--remove-orphans'], env=env, capture_output=True, check=True)


if __name__ == '__main__':
    main()
