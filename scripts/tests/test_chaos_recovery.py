import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from chaos_recovery_smoke import validate_environment, write_state, read_state


class ChaosOptInTests(unittest.TestCase):
    def test_requires_explicit_approval_and_local_endpoints(self):
        with patch.dict(os.environ, {"MAIL_CHAOS_APPROVED": "no"}):
            with self.assertRaises(ValueError):
                validate_environment()
        with patch.dict(os.environ, {"MAIL_CHAOS_APPROVED": "yes", "MAIL_API_URL": "https://production.test"}):
            with self.assertRaisesRegex(ValueError, "localhost"):
                validate_environment()
        with patch.dict(os.environ, {"MAIL_CHAOS_APPROVED": "yes", "MAIL_API_URL": "http://127.0.0.1:8080",
                                    "MAILPIT_URL": "http://localhost:8025"}):
            api, mailpit = validate_environment()
            self.assertEqual("http://127.0.0.1:8080", api)

    def test_state_does_not_store_credentials_and_cannot_be_reused(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'state.json'
            state={"phase":"setup", "run":"a"*32, "slug":"chaos-test", "recipient":"chaos@example.test"}
            write_state(path, state)
            self.assertEqual(state, read_state(path))
            with self.assertRaises(FileExistsError):
                write_state(path,state)
            state["phase"]="invalid"
            path.write_text(json.dumps(state))
            with self.assertRaises(ValueError):
                read_state(path)
