import tempfile
import unittest
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from generate_ci_evidence import verify_markers, REQUIRED_LIVE

class CiEvidenceTests(unittest.TestCase):
    def test_requires_real_markers_and_rejects_empty_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            with self.assertRaisesRegex(AssertionError,'missing'):
                verify_markers(folder)
            for marker in REQUIRED_LIVE:
                (folder/(marker+'.passed')).write_text('PASS\n')
            self.assertEqual(set(verify_markers(folder)),set(REQUIRED_LIVE))
            (folder/'openapi.passed').write_text('')
            with self.assertRaisesRegex(AssertionError,'openapi'):
                verify_markers(folder)

    def test_optional_tests_are_never_falsely_claimed(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            for marker in REQUIRED_LIVE:
                (folder/(marker+'.passed')).write_text('PASS')
            self.assertNotIn('broker_chaos',verify_markers(folder))
            (folder/'broker_chaos.passed').write_text('PASS')
            self.assertIn('broker_chaos',verify_markers(folder))
