"""No-network checks for the public, already-signed bootstrap release path."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import publish_bootstrap_release as publish
from release_signing import read_contract


class BootstrapRelease(unittest.TestCase):
    def test_import_contract_is_bound_to_permanent_certificate(self):
        record = json.loads((publish.ROOT / '.github/bootstrap-release.json').read_text())
        publish.check_record(record, read_contract())
        for key, value in [('certificate_sha256', '0'*64), ('signing_mode','temporary'), ('signed_apk','../bad.apk'), ('tag','vbad')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                publish.check_record(dict(record, **{key:value}), read_contract())

    def test_source_run_must_have_completed_successfully_at_exact_commit(self):
        record = json.loads((publish.ROOT / '.github/bootstrap-release.json').read_text())
        run = dict(status='completed',conclusion='success',head_sha=record['source_commit'],head_branch='main',path='.github/workflows/ci.yml')
        artifact = dict(id=record['ci_artifact_id'],name=record['ci_artifact'],expired=False,workflow_run={'id':record['ci_run_id']})
        publish.check_ci(record,run,artifact)
        for key,value in [('conclusion','failure'),('head_sha','0'*40),('head_branch','other')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                publish.check_ci(record,dict(run,**{key:value}),artifact)

    def test_signatures_can_change_but_application_metadata_cannot(self):
        with tempfile.TemporaryDirectory() as folder:
            first, second = Path(folder)/'unsigned.apk',Path(folder)/'signed.apk'
            for file,content in [(first,b'original'),(second,b'original')]:
                with zipfile.ZipFile(file,'w') as z:
                    z.writestr('AndroidManifest.xml',b'manifest')
                    z.writestr('META-INF/com/android/build/metadata.properties',content)
                    if file==second:z.writestr('META-INF/RELEASE.RSA',b'signature')
            self.assertEqual(publish.payload(first),publish.payload(second))
            with zipfile.ZipFile(second,'w') as z:
                z.writestr('AndroidManifest.xml',b'manifest')
                z.writestr('META-INF/com/android/build/metadata.properties',b'changed')
            self.assertNotEqual(publish.payload(first),publish.payload(second))


if __name__=='__main__':unittest.main()
