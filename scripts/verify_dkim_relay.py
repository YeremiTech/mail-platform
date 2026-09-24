#!/usr/bin/env python3
"""Cryptographic DKIM check of an actually delivered marketing RFC 822 message.

This is intentionally separate from Mailpit: Mailpit captures the unsigned message
before a production relay applies DKIM. A valid signature here requires DNS access.
Never use fabricated DKIM-Signature headers as proof of a signed production message.
"""
import argparse
from pathlib import Path
import re
from rfc8058 import parse_raw, require_one_click

REQUIRED = {"list-unsubscribe", "list-unsubscribe-post"}


def signed_fields(value):
    header = str(value).replace("\r", "").replace("\n", " ")
    def tag(name):
        match = re.search(r"(?:^|;)\s*" + name + r"\s*=\s*([^;]+)", header, re.I)
        return match.group(1).strip() if match else None
    domain = tag("d")
    h = tag("h")
    if not domain or not h:
        return None, set()
    return domain.lower(), {field.strip().lower() for field in h.split(":")}


def verify(raw, expected_origin, expected_dkim_domain):
    message = parse_raw(raw)
    require_one_click(message, expected_origin)
    signatures = message.get_all("DKIM-Signature", [])
    if not signatures:
        raise AssertionError("no downstream DKIM-Signature found; Mailpit alone cannot certify DKIM")
    try:
        import dkim  # dkimpy: must validate the actual signature against the published DNS key.
    except ImportError as exc:
        raise RuntimeError("Install dkimpy (pip install dkimpy) to verify relay signing") from exc
    for index, signature in enumerate(signatures):
        domain, fields = signed_fields(signature)
        if domain != expected_dkim_domain.lower() or not REQUIRED.issubset(fields):
            continue
        if dkim.DKIM(raw).verify(idx=index):
            return domain
    raise AssertionError("no valid relay DKIM signature from the expected domain covers both RFC 8058 headers")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("message", type=Path, help="Actual post-relay raw RFC 822 .eml message")
    parser.add_argument("--expected-origin", required=True, help="Public HTTPS marketing origin")
    parser.add_argument("--expected-dkim-domain", required=True, help="Expected DKIM d= domain")
    args = parser.parse_args()
    domain = verify(args.message.read_bytes(), args.expected_origin, args.expected_dkim_domain)
    print(f"PASS: cryptographic DKIM verification and both RFC 8058 signed headers ({domain})")


if __name__ == "__main__":
    main()
