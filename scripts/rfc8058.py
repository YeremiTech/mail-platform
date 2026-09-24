"""Strict inspection of RFC 8058 headers in raw SMTP messages captured by Mailpit."""
from email import policy
from email.parser import BytesParser
from urllib.parse import urlsplit, parse_qs
import re


def parse_raw(raw):
    if not isinstance(raw, bytes):
        raise TypeError("RFC 822 bytes required")
    message = BytesParser(policy=policy.default).parsebytes(raw)
    if message.defects:
        raise AssertionError("malformed message headers")
    return message


def require_one_click(message, expected_origin):
    unsubscribe = message.get_all("List-Unsubscribe", [])
    post = message.get_all("List-Unsubscribe-Post", [])
    if len(unsubscribe) != 1 or len(post) != 1:
        raise AssertionError("marketing message must have exactly one of each RFC 8058 header")
    if str(post[0]).strip() != "List-Unsubscribe=One-Click":
        raise AssertionError("invalid one-click POST header")
    match = re.fullmatch(r"<([^<>\s]+)>", str(unsubscribe[0]).strip())
    if not match:
        raise AssertionError("one-click header must contain exactly one HTTPS URL")
    url = match.group(1)
    parsed = urlsplit(url)
    expected = urlsplit(expected_origin)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise AssertionError("one-click URL must be HTTPS without credentials or fragment")
    if (parsed.scheme, parsed.netloc) != (expected.scheme, expected.netloc):
        raise AssertionError("unsubscribe URL does not use configured public origin")
    if parsed.path != "/api/v1/public/unsubscribe/one-click":
        raise AssertionError("one-click URL targets the wrong endpoint")
    qs = parse_qs(parsed.query, keep_blank_values=True)
    tokens = qs.get("token", [])
    if len(qs) != 1 or len(tokens) != 1 or not re.fullmatch(r"[A-Za-z0-9_-]{43}", tokens[0]):
        raise AssertionError("one-click URL must use a single opaque token")
    return url, tokens[0]


def require_transactional_without_unsubscribe(message):
    if message.get_all("List-Unsubscribe") or message.get_all("List-Unsubscribe-Post"):
        raise AssertionError("transactional message contains commercial unsubscribe headers")
