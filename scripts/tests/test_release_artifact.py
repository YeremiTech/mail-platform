import io
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from verify_release_artifact import verify

class ArtifactGuardTests(unittest.TestCase):
    def test_rejects_traversal_and_missing_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'bad.zip'
            with ZipFile(path,'w') as z:
                z.writestr('mail-platform/../../private.txt','bad')
            with self.assertRaisesRegex(AssertionError,'unsafe'):
                verify(path)
            with ZipFile(path,'w') as z:
                z.writestr('mail-platform/README.md','partial')
            with self.assertRaisesRegex(AssertionError,'required release files'):
                verify(path)
