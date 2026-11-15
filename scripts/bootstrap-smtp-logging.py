#!/usr/bin/env python3
"""Install Outset's narrow rsyslog path additions using authorized node sudo.

Keeps host profiles enforcing, backs up the original local include once, and
does not change container capabilities, RBAC or cluster-wide kernel settings.
"""
from pathlib import Path
import subprocess


def main():
    root = Path(__file__).resolve().parents[1]
    policy = (root / 'deploy/homelab/bootstrap/rsyslog-outset.local').read_bytes()
    script = r'''set -eu
target=/etc/apparmor.d/local/rsyslog-outset
backup=/var/backups/outset-mail-profile-20261115
if [ -e "$target" ] && ! sudo -n grep -q '^# Additional container paths for Outset' "$target"; then
  echo 'Existing unrelated policy file; refusing to replace it' >&2
  exit 1
fi
sudo -n install -d -m 700 "$backup"
sudo -n cp -an /etc/apparmor.d/local/usr.sbin.rsyslogd "$backup/local-original"
sudo -n tee "$target" >/dev/null
if ! sudo -n grep -q '^#include if exists <local/rsyslog-outset>$' /etc/apparmor.d/local/usr.sbin.rsyslogd; then
  printf '\n#include if exists <local/rsyslog-outset>\n' | sudo -n tee -a /etc/apparmor.d/local/usr.sbin.rsyslogd >/dev/null
fi
sudo -n apparmor_parser -r /etc/apparmor.d/usr.sbin.rsyslogd
'''
    for node in ['lab-01', 'lab-02', 'lab-03']:
        command = ['ssh'] + ([] if node == 'lab-01' else ['-J', 'lab-01']) + [node, script]
        result = subprocess.run(command, input=policy, capture_output=True)
        if result.returncode:
            raise RuntimeError(node + ': policy installation failed; inspect the private node backup')
        print(node + ': rsyslog image-path additions loaded; profile remains enforcing')


if __name__ == '__main__':
    main()
