#!/usr/bin/env python3
"""Install the owner's signing secrets, publish once, then pin Cube3's checksums.

Run with GitHub CLI authenticated as an owner who can administer Actions secrets
and write both repositories. Private files are read only from the backup folder;
only public release identities are written to Cube3.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import uuid
import zipfile

REPO = 'angelcode1/lightweight_androidtv_launcher'
CUBE = 'angelcode1/cube3'
MANIFEST = 'gazelle-debloat/assets/launcher/manifest.json'


def gh(*args, data=None):
    return subprocess.run(['gh', *args], input=data, capture_output=True, check=True).stdout


def api(path, payload=None, method=None):
    args = ['api', path]
    if method:
        args += ['--method', method]
    if payload is not None:
        args += ['--input', '-']
    raw = gh(*args, data=None if payload is None else json.dumps(payload).encode())
    return json.loads(raw) if raw.strip() else None


def optional_api(path):
    try:
        return api(path)
    except subprocess.CalledProcessError as exc:
        if b'HTTP 404' in (exc.stderr or b''):
            return None
        raise


def contents(repo, path):
    record = api(f'repos/{repo}/contents/{path}')
    return record, json.loads(base64.b64decode(record['content']))


def secret_values(record, folder):
    if (record.get('repository') != REPO or record.get('key_alias') != 'gazelle-launcher-release' or
            record.get('keystore') != 'gazelle-launcher-release.p12'):
        raise ValueError('Unexpected signing backup identity')
    return {
        'GAZELLE_SIGNING_KEYSTORE_B64': base64.b64encode((folder / record['keystore']).read_bytes()),
        'GAZELLE_SIGNING_STORE_PASSWORD': record['store_password'].encode(),
        'GAZELLE_SIGNING_KEY_ALIAS': record['key_alias'].encode(),
        'GAZELLE_SIGNING_KEY_PASSWORD': record['key_password'].encode(),
    }


def publish_release(record):
    tag = record['release_tag']
    existing = optional_api(f'repos/{REPO}/releases/tags/{tag}')
    if existing:
        return existing
    request_id = 'permanent-setup-' + uuid.uuid4().hex
    api(f'repos/{REPO}/actions/workflows/signed-release.yml/dispatches',
        {'ref': 'main', 'inputs': {'request_id': request_id}})
    run = None
    for _ in range(36):
        runs = api(f'repos/{REPO}/actions/workflows/signed-release.yml/runs?event=workflow_dispatch&branch=main&per_page=20')
        run = next((r for r in runs['workflow_runs'] if r.get('display_title') == 'Gazelle permanent release ' + request_id), None)
        if run:
            break
        time.sleep(5)
    if not run:
        raise RuntimeError('Release was requested but its run is not visible yet. Rerun this command to resume.')
    print('Watching permanent release: ' + run['html_url'], flush=True)
    subprocess.run(['gh', 'run', 'watch', str(run['id']), '--repo', REPO, '--exit-status'], check=True)
    return api(f'repos/{REPO}/releases/tags/{tag}')


def checked_assets(release, record, folder, fingerprint):
    if release.get('draft') or release.get('prerelease') or release.get('tag_name') != record['release_tag']:
        raise ValueError('Expected the stable permanent-key release')
    apk_name = 'gazelle-launcher-' + record['version_name'] + '-release-signed.apk'
    module_name = 'gazelle-launcher-privileged-v' + str(record['version_code']) + '.zip'
    names = [apk_name, module_name, 'SIGNING-MODE.txt', 'release-signing.json',
             'APK-CERTIFICATE.txt', 'source-provenance.json', 'SHA256SUMS.txt']
    args = ['release', 'download', record['release_tag'], '--repo', REPO, '--dir', str(folder)]
    for name in names:
        args += ['--pattern', name]
    gh(*args)
    rows = {}
    for line in (folder / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        if not re.fullmatch(r'[0-9a-f]{64}', digest) or name in rows:
            raise ValueError('Malformed/duplicate release checksum')
        rows[name] = digest
    assets = {a['name']: a for a in release['assets']}
    if len(assets) != len(release['assets']):
        raise ValueError('Duplicate release asset')
    for name in names:
        raw = (folder / name).read_bytes()
        actual = hashlib.sha256(raw).hexdigest()
        asset = assets[name]
        if asset.get('size') != len(raw) or asset.get('digest') != 'sha256:' + actual:
            raise ValueError('Release asset identity mismatch: ' + name)
        if name != 'SHA256SUMS.txt' and rows.get(name) != actual:
            raise ValueError('Release checksum mismatch: ' + name)
    contract = json.loads((folder / 'release-signing.json').read_text())
    if contract.get('certificate_sha256') != fingerprint or contract.get('mode') != 'permanent':
        raise ValueError('Released signing contract differs from the permanent key')
    if (folder / 'SIGNING-MODE.txt').read_text().strip() != 'permanent':
        raise ValueError('Refuse a temporary-key release')
    report = (folder / 'APK-CERTIFICATE.txt').read_text()
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)\s*$', report, re.M)
    if len(signers) != 1 or signers[0].replace(':', '').lower() != fingerprint:
        raise ValueError('Released APK certificate report differs from the permanent key')
    with zipfile.ZipFile(folder / module_name) as z:
        if z.testzip() or len(z.namelist()) != len(set(z.namelist())):
            raise ValueError('Invalid launcher module archive')
        embedded = z.read('system/priv-app/GazelleLauncher/GazelleLauncher.apk')
        if embedded != (folder / apk_name).read_bytes():
            raise ValueError('Module and standalone APK differ')
        props = dict(line.split('=', 1) for line in z.read('module.prop').decode().splitlines() if '=' in line)
        if props.get('id') != 'gazelle_launcher_privileged' or props.get('versionCode') != str(record['version_code']):
            raise ValueError('Module identity/version mismatch')
    provenance = json.loads((folder / 'source-provenance.json').read_text())
    if (provenance.get('repository') != REPO or provenance.get('signing_mode') != 'permanent' or
            provenance.get('version_code') != record['version_code'] or
            provenance.get('version_name') != record['version_name'] or
            not re.fullmatch(r'[0-9a-f]{40}', provenance.get('commit', ''))):
        raise ValueError('Invalid permanent release provenance')
    return assets[apk_name], assets[module_name], provenance


def link_cube(record, release, assets, fingerprint):
    remote, old = contents(CUBE, MANIFEST)
    apk, module, source = assets
    updated = dict(old)
    updated.update(source_repository=REPO, source_version_commit=source['commit'],
                   source_release_tag=release['tag_name'], source_release_id=release['id'],
                   source_release_url=release['html_url'], signing_mode='permanent',
                   origin='Permanent-key upstream release; exact APK/module bytes verified against SHA256SUMS.txt and GitHub asset digests.',
                   archive=module['name'], archive_sha256=module['digest'].removeprefix('sha256:'),
                   archive_size=module['size'], archive_url=module['browser_download_url'],
                   apk_sha256=apk['digest'].removeprefix('sha256:'), apk_url=apk['browser_download_url'],
                   checksum_url=f'https://github.com/{REPO}/releases/download/{release["tag_name"]}/SHA256SUMS.txt',
                   version_name=record['version_name'], version_code=record['version_code'],
                   signer_sha256=fingerprint, signer='CN=Gazelle Launcher Release, OU=Android, O=angelcode1',
                   upgrade_policy='Permanent certificate pinned. No automatic uninstall, downgrade or replacement of a different installed signer.')
    if old == updated:
        print('Cube3 already links this exact permanent-key APK checksum.')
        return
    if old.get('signing_mode') == 'permanent' and old.get('signer_sha256') != fingerprint:
        raise ValueError('Cube3 already pins a different permanent signer; refusing key rotation')
    body = {'message': 'Pin permanent-key Gazelle Launcher ' + record['version_name'] + ' release checksums',
            'content': base64.b64encode((json.dumps(updated, indent=2) + '\n').encode()).decode(),
            'sha': remote['sha'], 'branch': 'main'}
    api(f'repos/{CUBE}/contents/{MANIFEST}', body, method='PUT')
    print('Cube3 pinned APK SHA256: ' + updated['apk_sha256'])
    print('Release: ' + release['html_url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup', nargs='?', type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    if not shutil.which('gh'):
        raise SystemExit('Install GitHub CLI (brew install gh), run gh auth login, then rerun this command.')
    gh('auth', 'status')
    record = json.loads((args.backup / 'signing-secrets.json').read_text())
    fingerprint = hashlib.sha256((args.backup / 'gazelle-launcher-release.cer').read_bytes()).hexdigest()
    _, contract = contents(REPO, '.github/release-signing.json')
    if contract.get('certificate_sha256') != fingerprint or contract.get('key_alias') != record['key_alias']:
        raise SystemExit('Backup does not match the repository permanent certificate')
    if record['release_tag'] != 'v' + record['version_name']:
        raise ValueError('Backup version/tag mismatch')
    _, cube_signing = contents(CUBE, 'gazelle-debloat/assets/launcher/permanent-release.json')
    if cube_signing.get('certificate_sha256') != fingerprint:
        raise ValueError('Cube3 has not pinned this permanent certificate')
    _, cube_contract = contents(CUBE, MANIFEST)
    if cube_contract.get('signing_mode') == 'permanent' and cube_contract.get('signer_sha256') != fingerprint:
        raise ValueError('Cube3 pins a different permanent signer; refusing key replacement')
    print('Installing the four permanent signing secrets; private values are not printed.', flush=True)
    for name, value in secret_values(record, args.backup).items():
        gh('secret', 'set', name, '--repo', REPO, data=value)
    release = publish_release(record)
    with tempfile.TemporaryDirectory(prefix='gazelle-permanent-release-') as folder:
        assets = checked_assets(release, record, Path(folder), fingerprint)
        link_cube(record, release, assets, fingerprint)
    print('Permanent release published and Cube3 checksum link updated.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as exc:
        # gh may return request details; print only the exit status, never stdin.
        raise SystemExit('GitHub operation failed (exit ' + str(exc.returncode) + '). Check account permissions or the linked Actions run, then rerun.')
