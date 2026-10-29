#!/usr/bin/env python3
"""Require the permanent launcher key and verify every published APK signer."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

CONTRACT = Path(__file__).resolve().parents[1] / '.github/release-signing.json'
SECRETS = ('KEYSTORE_B64', 'STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')


def read_contract(path=CONTRACT):
    record = json.loads(path.read_text())
    if (record.get('schema') != 1 or record.get('mode') != 'permanent' or
            not re.fullmatch(r'[0-9a-f]{64}', record.get('certificate_sha256', '')) or
            not re.fullmatch(r'[a-z0-9-]+', record.get('key_alias', ''))):
        raise ValueError('Invalid permanent signing contract')
    return record


def prepare(output, record, env=None):
    env = dict(os.environ if env is None else env)
    if any(not env.get(name) for name in SECRETS):
        raise ValueError('Configure all four GAZELLE_SIGNING_* Actions secrets. Temporary signing is disabled.')
    if env['KEY_ALIAS'] != record['key_alias']:
        raise ValueError('Signing alias differs from the permanent signing contract')
    raw = base64.b64decode(env['KEYSTORE_B64'], validate=True)
    if not raw or len(raw) > 65536:
        raise ValueError('Invalid permanent keystore size')
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(raw)
        cert = subprocess.run(['keytool', '-exportcert', '-keystore', str(output),
                               '-storetype', 'PKCS12', '-alias', env['KEY_ALIAS'],
                               '-storepass:env', 'STORE_PASSWORD'], env=env,
                              capture_output=True, check=True).stdout
        if hashlib.sha256(cert).hexdigest() != record['certificate_sha256']:
            raise ValueError('Keystore certificate differs from the pinned permanent signer')
    except Exception:
        output.unlink(missing_ok=True)
        raise
    print('PERMANENT_SIGNING_KEY_VERIFIED certificate_sha256=' + record['certificate_sha256'])


def verify_report(report, record):
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)\s*$',
                         report, flags=re.MULTILINE)
    if len(digests) != 1 or digests[0].replace(':', '').lower() != record['certificate_sha256']:
        raise ValueError('APK must have exactly the pinned permanent signer')
    print('PERMANENT_APK_SIGNER_VERIFIED certificate_sha256=' + record['certificate_sha256'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'verify'))
    parser.add_argument('path', type=Path)
    args = parser.parse_args()
    record = read_contract()
    if args.action == 'prepare':
        prepare(args.path, record)
    else:
        verify_report(args.path.read_text(), record)


if __name__ == '__main__':
    main()
