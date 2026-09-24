#!/usr/bin/env python3
"""Smoke test against a running API, RabbitMQ, PostgreSQL and Mailpit.

Set MAIL_API_URL, MAILPIT_URL, E2E_CLIENT_ID and E2E_API_KEY. This script
uses only the Python standard library and never prints the recovery code.
"""

import json
import os
import re
import sys
import time
import uuid
from rfc8058 import parse_raw, require_transactional_without_unsubscribe
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


API = os.environ.get("MAIL_API_URL", "http://127.0.0.1:8080").rstrip("/")
MAILPIT = os.environ.get("MAILPIT_URL", "http://127.0.0.1:8025").rstrip("/")
CLIENT = os.environ["E2E_CLIENT_ID"]
KEY = os.environ["E2E_API_KEY"]
AUTH = {"X-Client-Id": CLIENT, "X-Internal-Api-Key": KEY}
DEADLINE_SECONDS = 90


def request(base, path, method="GET", payload=None, headers=None):
    req = Request(base + path, data=payload, method=method, headers=headers or {})
    try:
        with urlopen(req, timeout=8) as response:
            return response.status, response.read()
    except HTTPError as exc:
        return exc.code, exc.read()


def api(path, method="GET", data=None, headers=None, expected=(200,)):
    request_headers = dict(AUTH)
    request_headers.update(headers or {})
    payload = None if data is None else json.dumps(data).encode("utf-8")
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    status, body = request(API, path, method, payload, request_headers)
    if status not in expected:
        raise AssertionError(f"{method} {path}: HTTP {status}: {body[:500]!r}")
    return json.loads(body) if body else None


def wait_for(label, operation, timeout=DEADLINE_SECONDS):
    end = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < end:
        try:
            value = operation()
            if value:
                return value
        except (URLError, TimeoutError, OSError, ValueError) as exc:
            last_error = exc
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {label}: {last_error}")


def mailpit_messages():
    status, body = request(MAILPIT, "/api/v1/messages")
    if status != 200:
        raise AssertionError(f"Mailpit returned HTTP {status}: {body[:200]!r}")
    return json.loads(body).get("messages", [])


def message_for(address):
    for message in mailpit_messages():
        if any(item.get("Address", "").lower() == address.lower()
               for item in message.get("To", [])):
            status, body = request(MAILPIT, "/api/v1/message/" + message["ID"])
            if status == 200:
                return json.loads(body)
    return None


def upload_pdf(pdf):
    boundary = "mail-platform-" + uuid.uuid4().hex
    body = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="file"; filename="copy.pdf"\r\n'
        "Content-Type: application/pdf\r\n\r\n"
    ).encode("ascii") + pdf + f"\r\n--{boundary}--\r\n".encode("ascii")
    status, response = request(API, "/api/v1/attachments", "POST", body,
                               {**AUTH, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    if status != 201:
        raise AssertionError(f"PDF upload: HTTP {status}: {response[:500]!r}")
    return json.loads(response)["id"]


def main():
    wait_for("API health", lambda: request(API, "/actuator/health")[0] == 200)
    run_id = uuid.uuid4().hex
    recovery_address = f"recovery-{run_id}@example.test"
    subject = "e2e-user-" + run_id
    challenge = api("/api/v1/password-recovery/challenges", "POST",
                    {"subjectReference": subject, "email": recovery_address}, expected=(202,))
    challenge_id = challenge["challengeId"]
    recovery_mail = wait_for("recovery email", lambda: message_for(recovery_address))
    match = re.search(r"(?<!\d)\d{6}(?!\d)", recovery_mail.get("Text", ""))
    if not match:
        raise AssertionError("Recovery email does not contain a six-digit code")
    wrong_code = "000000" if match.group(0) != "000000" else "111111"
    status, _ = request(API, f"/api/v1/password-recovery/challenges/{challenge_id}/verify",
                        "POST", json.dumps({"code": wrong_code}).encode(),
                        {**AUTH, "Content-Type": "application/json"})
    if status != 400:
        raise AssertionError(f"Invalid recovery code should return 400, got {status}")
    grant = api(f"/api/v1/password-recovery/challenges/{challenge_id}/verify", "POST",
                {"code": match.group(0)})
    consumed = api("/api/v1/password-recovery/grants/consume", "POST",
                   {"grantId": grant["grantId"], "resetGrant": grant["resetGrant"]})
    if consumed["subjectReference"] != subject:
        raise AssertionError("Reset grant returned the wrong account")

    pdf = b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n"
    attachment_id = upload_pdf(pdf)
    recipient_a = f"copy-a-{run_id}@example.test"
    recipient_b = f"copy-b-{run_id}@example.test"
    copy_request = {
        "documentType": "FACTURA", "documentNumber": "F001-123",
        "customerName": "Cliente", "issuerName": "Emisor",
        "amount": 25.50, "currency": "PEN",
        "recipients": [{"email": recipient_a}, {"email": recipient_b}],
        "attachmentIds": [attachment_id],
    }
    idempotency_key = "e2e-document-" + run_id
    path = "/api/v1/documents/copies"
    sent = api(path, "POST", copy_request, {"Idempotency-Key": idempotency_key}, (202,))
    repeated = api(path, "POST", copy_request, {"Idempotency-Key": idempotency_key}, (202,))
    if sent["id"] != repeated["id"]:
        raise AssertionError("Idempotent document request created a second message")
    wait_for("document delivery", lambda: api(f"/api/v1/emails/{sent['id']}")["status"] == "SENT")
    document_mail = wait_for("document email", lambda: message_for(recipient_a))
    raw_status, raw_mail = request(MAILPIT, f"/api/v1/message/{document_mail['ID']}/raw")
    if raw_status != 200:
        raise AssertionError("Mailpit could not provide the raw transactional message")
    require_transactional_without_unsubscribe(parse_raw(raw_mail))
    addresses = {item.get("Address", "").lower() for item in document_mail.get("To", [])}
    if recipient_b.lower() not in addresses:
        raise AssertionError("Second document recipient is missing")
    parts = document_mail.get("Attachments") or []
    if not parts or parts[0].get("FileName") != "copy.pdf":
        raise AssertionError("PDF attachment is missing from SMTP message")
    part_id = parts[0]["PartID"]
    status, received_pdf = request(MAILPIT, f"/api/v1/message/{document_mail['ID']}/part/{part_id}")
    if status != 200 or received_pdf != pdf:
        raise AssertionError("SMTP PDF bytes differ from the uploaded document")
    print("E2E OK: recovery email, one-time grant, two recipients, exact PDF and idempotency")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"E2E FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
