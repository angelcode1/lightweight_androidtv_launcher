#!/usr/bin/env python3
"""Publish the first locally signed permanent APK, rechecking its successful CI source.

No private signing key is accepted or loaded. Future source builds use the normal
permanent-secret workflow; this import only carries the first verified public APK.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile

from release_signing import read_contract, verify_report

ROOT = Path(__file__).resolve().parents[1]
REPO = 'angelcode1/lightweight_androidtv_launcher'


def gh_json(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def payload(archive):
    with zipfile.ZipFile(archive) as z:
        if z.testzip() or len(z.namelist()) != len(set(z.namelist())):
            raise ValueError('Invalid or duplicate APK entries')
        def signature(name):
            upper = name.upper()
            return upper.startswith('META-INF/') and (upper.endswith(('.SF', '.RSA', '.DSA', '.EC')) or upper == 'META-INF/MANIFEST.MF')
        return {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not signature(n)}


def check_record(record, contract):
    expected = 'release-bootstrap/gazelle-launcher-' + record['version_name'] + '-release-signed.apk'
    if (record.get('schema') != 1 or record.get('signing_mode') != 'permanent' or
            not re.fullmatch(r'[0-9A-Za-z.-]+', record['version_name']) or
            type(record.get('version_code')) is not int or record['version_code'] < contract['first_version_code'] or
            record.get('tag') != 'v' + record['version_name'] or record.get('signed_apk') != expected or
            record.get('certificate_sha256') != contract['certificate_sha256'] or
            any(not re.fullmatch(r'[0-9a-f]{64}', record.get(k, ''))
                for k in ('unsigned_apk_sha256', 'signed_apk_sha256')) or
            not re.fullmatch(r'[0-9a-f]{40}', record.get('source_commit', '')) or
            record.get('ci_artifact') != 'gazelle-release-unsigned-' + record['source_commit'] or
            any(type(record.get(k)) is not int or record[k] <= 0 for k in ('ci_run_id', 'ci_artifact_id'))):
        raise ValueError('Invalid first permanent release import contract')


def check_ci(record, run, artifact):
    if (run.get('conclusion') != 'success' or run.get('status') != 'completed' or
            run.get('head_sha') != record['source_commit'] or run.get('head_branch') != 'main' or
            run.get('path') != '.github/workflows/ci.yml' or
            artifact.get('expired') or artifact.get('id') != record['ci_artifact_id'] or
            artifact.get('name') != record['ci_artifact'] or
            artifact.get('workflow_run', {}).get('id') != record['ci_run_id']):
        raise ValueError('First signed release must come from the exact successful main CI build')


def stage(record, contract, bt, signed, output):
    if digest(signed) != record['signed_apk_sha256']:
        raise ValueError('Signed bootstrap APK checksum mismatch')
    subprocess.run([str(bt / 'zipalign'), '-c', '4', str(signed)], check=True)
    badging = subprocess.check_output([str(bt / 'aapt'), 'dump', 'badging', str(signed)], text=True)
    package = badging.splitlines()[0]
    if any(value not in package for value in ("name='com.gazelle.launcher'",
            "versionCode='" + str(record['version_code']) + "'", "versionName='" + record['version_name'] + "'")):
        raise ValueError('Signed bootstrap APK package/version mismatch')
    report = subprocess.check_output([str(bt / 'apksigner'), 'verify', '--verbose', '--print-certs', str(signed)], text=True)
    verify_report(report, contract)
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(signed, output / signed.name)
    (output / 'APK-CERTIFICATE.txt').write_text(report)
    (output / 'SIGNING-MODE.txt').write_text('permanent\n')
    (output / 'release-signing.json').write_text(json.dumps(contract, indent=2) + '\n')
    provenance = dict(repository=REPO, commit=record['source_commit'], version_name=record['version_name'],
                      version_code=record['version_code'], signing_mode='permanent', ci_run_id=record['ci_run_id'],
                      ci_artifact_id=record['ci_artifact_id'], unsigned_apk_sha256=record['unsigned_apk_sha256'],
                      application_payload_unchanged=True, bootstrap_import_commit=os.environ.get('GITHUB_SHA'))
    (output / 'source-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    prop = ('id=gazelle_launcher_privileged\nname=Gazelle Launcher Privileged\nversion=' + record['version_name'] +
            '\nversionCode=' + str(record['version_code']) + '\nauthor=angelcode1\n'
            'description=Installs Gazelle Launcher as a privileged Fire OS system launcher\n')
    module = output / ('gazelle-launcher-privileged-v' + str(record['version_code']) + '.zip')
    with zipfile.ZipFile(module, 'x', zipfile.ZIP_DEFLATED) as z:
        for name, raw in [('module.prop', prop.encode()), ('system/priv-app/GazelleLauncher/GazelleLauncher.apk', signed.read_bytes())]:
            info = zipfile.ZipInfo(name, (2026, 10, 29, 0, 0, 0)); info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16; info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, raw)
    checksums = ''.join(digest(p) + '  ' + p.name + '\n' for p in sorted(output.iterdir()))
    (output / 'SHA256SUMS.txt').write_text(checksums)
    print('PERMANENT_RELEASE_ASSETS_VERIFIED apk_sha256=' + record['signed_apk_sha256'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('dist'))
    args = parser.parse_args()
    record = json.loads((ROOT / '.github/bootstrap-release.json').read_text())
    contract = read_contract(); check_record(record, contract)
    run = gh_json(f'repos/{REPO}/actions/runs/{record["ci_run_id"]}')
    artifact = gh_json(f'repos/{REPO}/actions/artifacts/{record["ci_artifact_id"]}')
    check_ci(record, run, artifact)
    signed = ROOT / record['signed_apk']
    with tempfile.TemporaryDirectory(prefix='gazelle-original-ci-') as directory:
        subprocess.run(['gh', 'run', 'download', str(record['ci_run_id']), '--repo', REPO,
                        '--name', record['ci_artifact'], '--dir', directory], check=True)
        unsigned = Path(directory) / 'app-release-unsigned.apk'
        if digest(unsigned) != record['unsigned_apk_sha256'] or payload(unsigned) != payload(signed):
            raise ValueError('Signing changed the original CI-built application payload')
    stage(record, contract, args.build_tools, signed, args.output)
    notes = (f'Fire TV Cube 3 Gazelle Launcher {record["version_name"]}.\n\n'
             f'Version code: {record["version_code"]}\nSigning mode: permanent\n'
             f'Certificate SHA256: {contract["certificate_sha256"]}\n\n'
             f'Source: {record["source_commit"]}\nCI: https://github.com/{REPO}/actions/runs/{record["ci_run_id"]}\n\n'
             'Tests, lint and R8 release build passed. The exact CI APK was signed with the permanent owner key; '
             'application entries are unchanged. APK and privileged module contain identical signed application bytes. '
             'SHA256SUMS.txt lists the released assets. This first release imports the public signed APK without '
             'placing the private key in Git or Actions. Future source releases require the four permanent signing secrets. '
             'Older temporary/test-key installations have a different certificate and cannot be upgraded in place.\n')
    with tempfile.NamedTemporaryFile(mode='w', suffix='.md', delete=False) as f:
        f.write(notes); notes_path = Path(f.name)
    try:
        subprocess.run(['gh', 'release', 'create', record['tag'], *map(str, sorted(args.output.iterdir())),
                        '--repo', REPO, '--target', record['source_commit'], '--latest',
                        '--title', 'Gazelle Launcher ' + record['version_name'], '--notes-file', str(notes_path)], check=True)
    finally:
        notes_path.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
