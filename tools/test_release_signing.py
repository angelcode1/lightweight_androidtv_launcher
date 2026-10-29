"""No-network tests for permanent signer and cross-repository checksum wiring."""
import base64
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release_signing as signing
import setup_permanent_release as setup


class PermanentSigning(unittest.TestCase):
    def record(self, raw=b'certificate'):
        return {'key_alias': 'gazelle-launcher-release', 'certificate_sha256': hashlib.sha256(raw).hexdigest()}

    def env(self):
        return {'KEYSTORE_B64': base64.b64encode(b'keystore').decode(), 'STORE_PASSWORD': 'test-store',
                'KEY_ALIAS': 'gazelle-launcher-release', 'KEY_PASSWORD': 'test-key'}

    def test_missing_and_partial_secrets_never_create_temporary_key(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'key.p12'
            for env in ({}, {'KEYSTORE_B64': 'AA=='}):
                with patch.object(signing.subprocess, 'run') as command, self.assertRaises(ValueError):
                    signing.prepare(path, self.record(), env)
                command.assert_not_called()
                self.assertFalse(path.exists())

    def test_certificate_mismatch_removes_private_output(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'key.p12'
            with patch.object(signing.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'wrong')), self.assertRaises(ValueError):
                signing.prepare(path, self.record(), self.env())
            self.assertFalse(path.exists())

    def test_valid_keystore_private_mode_and_no_secret_command_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'key.p12'
            with patch.object(signing.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'certificate')) as command:
                signing.prepare(path, self.record(), self.env())
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertNotIn('test-store', command.call_args.args[0])
            self.assertIn('STORE_PASSWORD', command.call_args.args[0])
            with self.assertRaises(FileExistsError):
                signing.prepare(path, self.record(), self.env())

    def test_apk_requires_one_pinned_signer(self):
        record = self.record(); digest = record['certificate_sha256']
        report = 'Signer #1 certificate SHA-256 digest: ' + digest + '\n'
        signing.verify_report(report, record)
        for bad in ('', report.replace(digest, '0' * 64), report + report.replace('#1', '#2')):
            with self.assertRaises(ValueError):
                signing.verify_report(bad, record)

    def test_release_cannot_publish_a_temporary_prerelease(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/signed-release.yml').read_text()
        self.assertNotIn('keytool -genkeypair', workflow)
        self.assertNotIn('--prerelease', workflow)
        self.assertIn('--latest', workflow)
        self.assertIn('release_signing.py prepare', workflow)
        self.assertIn('release_signing.py verify', workflow)
        self.assertIn('if: always()', workflow)
        self.assertNotIn('path: ${{ runner.temp }}', workflow)


class CubeChecksumWiring(unittest.TestCase):
    def fixtures(self):
        record = {'release_tag': 'v1.6.1-gazelle.15', 'version_name': '1.6.1-gazelle.15', 'version_code': 10616}
        release = {'id': 1, 'tag_name': record['release_tag'], 'html_url': 'https://github.com/' + setup.REPO + '/releases/tag/' + record['release_tag']}
        def asset(name, digest):
            return {'name': name, 'digest': 'sha256:' + digest, 'size': 10,
                    'browser_download_url': 'https://github.com/' + setup.REPO + '/releases/download/' + record['release_tag'] + '/' + name}
        return record, release, (asset('release.apk', '1' * 64), asset('module.zip', '2' * 64), {'commit': 'a' * 40})

    def test_manifest_links_actual_asset_checksums_without_private_values(self):
        record, release, assets = self.fixtures()
        old = {'package': 'com.gazelle.launcher', 'apk_path': 'system/priv-app/GazelleLauncher/GazelleLauncher.apk'}
        with patch.object(setup, 'contents', return_value=({'sha': 'blob-sha'}, old)), patch.object(setup, 'api') as update:
            setup.link_cube(record, release, assets, '3' * 64)
        self.assertEqual(update.call_args.kwargs, {'method': 'PUT'})
        body = update.call_args.args[1]
        self.assertEqual(body['sha'], 'blob-sha')
        manifest = json.loads(base64.b64decode(body['content']))
        self.assertEqual(manifest['apk_sha256'], '1' * 64)
        self.assertEqual(manifest['archive_sha256'], '2' * 64)
        self.assertEqual(manifest['signing_mode'], 'permanent')
        self.assertEqual(manifest['signer_sha256'], '3' * 64)
        self.assertTrue(manifest['checksum_url'].endswith('/SHA256SUMS.txt'))
        self.assertFalse(any('password' in name or 'keystore' in name for name in manifest))

    def test_different_existing_permanent_key_never_replaced(self):
        record, release, assets = self.fixtures()
        with patch.object(setup, 'contents', return_value=({'sha': 'blob'}, {'signing_mode': 'permanent', 'signer_sha256': '0' * 64})), patch.object(setup, 'api') as update, self.assertRaises(ValueError):
            setup.link_cube(record, release, assets, '3' * 64)
        update.assert_not_called()

    def test_secret_uploads_use_stdin(self):
        with patch.object(setup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'')) as command:
            setup.gh('secret', 'set', 'EXAMPLE', '--repo', setup.REPO, data=b'private-value')
        self.assertNotIn('private-value', ' '.join(command.call_args.args[0]))
        self.assertEqual(command.call_args.kwargs['input'], b'private-value')


if __name__ == '__main__':
    unittest.main()
