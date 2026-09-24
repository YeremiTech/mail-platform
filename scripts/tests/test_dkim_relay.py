import sys
from pathlib import Path
from unittest import TestCase
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_dkim_relay import signed_fields, verify
from rfc8058 import parse_raw

ORIGIN = "https://mail.example.test"
TOKEN = "A" * 43
MAIL = (f"From: me@example.test\r\nTo: you@example.test\r\n"
        f"List-Unsubscribe: <{ORIGIN}/api/v1/public/unsubscribe/one-click?token={TOKEN}>\r\n"
        "List-Unsubscribe-Post: List-Unsubscribe=One-Click\r\n"
        "Subject: Test\r\n\r\nBody\r\n").encode()


class DkimGuardTests(TestCase):
    def test_requires_real_dkim_signature(self):
        with self.assertRaisesRegex(AssertionError, "no downstream DKIM"):
            verify(MAIL, ORIGIN, "example.test")

    def test_header_coverage_must_include_both_names(self):
        domain, signed = signed_fields("v=1; a=rsa-sha256; d=example.test; h=From:To:List-Unsubscribe;")
        self.assertEqual("example.test", domain)
        self.assertNotIn("list-unsubscribe-post", signed)
        domain, signed = signed_fields("v=1;d=example.test;h=from:list-unsubscribe:LIST-UNSUBSCRIBE-POST")
        self.assertEqual("example.test", domain)
        self.assertIn("list-unsubscribe-post", signed)
