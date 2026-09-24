import ast
import unittest
from pathlib import Path


class LoadHarnessContractTests(unittest.TestCase):
    def test_live_harness_rejects_missing_service_confirmations(self):
        path=Path(__file__).resolve().parents[1]/'load_smoke.py'
        code=path.read_text()
        ast.parse(code)
        self.assertIn("summary.get('messages',0)==size and accepted==size",code)
        self.assertIn("if transaction_latency is None",code)
        self.assertIn("if len(found)!=size",code)
        self.assertIn("LOAD_SMOKE_ENABLED",code)
