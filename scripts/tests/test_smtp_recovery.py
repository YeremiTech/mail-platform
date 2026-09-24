import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from chaos_recovery_smoke import validate_environment

class SmtpRecoverySafetyTests(unittest.TestCase):
    def test_disallows_remote_or_production_endpoints(self):
        with patch.dict(os.environ, {'MAIL_CHAOS_APPROVED':'yes', 'MAIL_API_URL':'https://production.example.com'}, clear=True):
            with self.assertRaisesRegex(ValueError,'localhost'):
                validate_environment()

    def test_requires_two_explicit_opt_ins(self):
        script=Path(__file__).resolve().parents[1]/'smtp_recovery_smoke.py'
        with patch.dict(os.environ, {'MAIL_CHAOS_APPROVED':'yes', 'SMTP_OUTAGE_APPROVED':''}, clear=True):
            result=subprocess.run([sys.executable,str(script),'setup'],capture_output=True,text=True,timeout=15)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('SMTP_OUTAGE_APPROVED',result.stderr)

if __name__ == '__main__': unittest.main()
