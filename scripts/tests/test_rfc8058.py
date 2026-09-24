from email import policy
from email.message import EmailMessage
import sys
from pathlib import Path
from unittest import TestCase
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from rfc8058 import parse_raw, require_one_click, require_transactional_without_unsubscribe

ORIGIN = "https://mail.example.test"
TOKEN = "A" * 43


def message(url=ORIGIN + "/api/v1/public/unsubscribe/one-click?token=" + TOKEN):
    msg = EmailMessage()
    msg["From"] = "sender@example.test"
    msg["To"] = "recipient@example.test"
    msg["List-Unsubscribe"] = f"<{url}>"
    msg["List-Unsubscribe-Post"] = "List-Unsubscribe=One-Click"
    msg.set_content("Offer")
    return parse_raw(msg.as_bytes(policy=policy.SMTP))


class Rfc8058Tests(TestCase):
    def test_expected_headers_accepted(self):
        url, token = require_one_click(message(), ORIGIN)
        self.assertEqual(TOKEN, token)
        self.assertTrue(url.startswith(ORIGIN))

    def test_http_or_wrong_host_rejected(self):
        for url in (
            "http://mail.example.test/api/v1/public/unsubscribe/one-click?token=" + TOKEN,
            "https://wrong.example.test/api/v1/public/unsubscribe/one-click?token=" + TOKEN,
            "https://mail.example.test/api/v1/public/unsubscribe/one-click?token=" + TOKEN + "&x=y",
        ):
            with self.assertRaises(AssertionError):
                require_one_click(message(url), ORIGIN)

    def test_transactional_mail_has_no_headers(self):
        raw = b"From: example@example.test\r\nTo: alice@example.test\r\nSubject: Receipt\r\n\r\nThanks"
        require_transactional_without_unsubscribe(parse_raw(raw))
        with self.assertRaises(AssertionError):
            require_transactional_without_unsubscribe(message())
