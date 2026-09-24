#!/usr/bin/env python3
"""Smoke test for v0.5 enhancements against an isolated API, PostgreSQL,
RabbitMQ and Mailpit. Uses stdlib; never logs credentials, reset codes or PII.
Required: ADMIN_API_KEY and E2E_API_KEY (legacy client used for isolation).
"""
import datetime
import json
import os
import sys
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from urllib.parse import urlencode, urlsplit
from rfc8058 import parse_raw, require_one_click

API = os.getenv("MAIL_API_URL", "http://127.0.0.1:8080").rstrip("/")
MAILPIT = os.getenv("MAILPIT_URL", "http://127.0.0.1:8025").rstrip("/")
ADMIN = os.environ["ADMIN_API_KEY"]
LEGACY = os.environ["E2E_API_KEY"]
LEGACY_ID = os.environ.get("E2E_CLIENT_ID", "inventory")
TIMEOUT = 90


def call(method, path, data=None, auth=None, headers=None, expected=(200,)):
    req_headers = {**(auth or {}), **(headers or {})}
    body = None if data is None else json.dumps(data).encode("utf-8")
    if body is not None:
        req_headers["Content-Type"] = "application/json"
    request = Request(API + path, data=body, headers=req_headers, method=method)
    try:
        with urlopen(request, timeout=10) as response:
            status, payload = response.status, response.read()
    except HTTPError as error:
        status, payload = error.code, error.read()
    if status not in expected:
        # Truncate and scrub HTTP error details: only safe server-side messages belong in test logs.
        raise AssertionError(f"{method} {path}: expected {expected}, got HTTP {status}")
    return json.loads(payload) if payload else None


def wait_until(description, predicate, timeout=TIMEOUT):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            found = predicate()
            if found:
                return found
        except (URLError, TimeoutError, OSError):
            pass
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {description}")


def main():
    wait_until("API health", lambda: call("GET", "/actuator/health").get("status") == "UP")
    suffix = uuid.uuid4().hex[:16]
    client_id = "smoke-" + suffix
    admin_headers = {"X-Client-Id": "admin", "X-Internal-Api-Key": ADMIN}
    legacy_headers = {"X-Client-Id": LEGACY_ID, "X-Internal-Api-Key": LEGACY}
    created = call("POST", "/api/v1/admin/clients", {
        "clientId": client_id, "displayName": "Upgrade isolated smoke client",
        "requestsPerMinute": 500, "ttlDays": 1,
        "permissions": ["EMAIL_SEND", "EMAIL_READ", "EMAIL_CANCEL", "TEMPLATE_READ",
                        "TEMPLATE_WRITE", "CAMPAIGN_READ", "CAMPAIGN_WRITE", "BATCH_READ",
                        "BATCH_WRITE", "SUPPRESSION_READ", "SUPPRESSION_WRITE"],
    }, auth=admin_headers, expected=(201,))
    assert created["clientId"] == client_id
    client_headers = {"X-Client-Id": client_id, "X-Internal-Api-Key": created["apiKey"]}
    call("GET", "/api/v1/admin/clients", auth=client_headers, expected=(403,))
    call("POST", "/api/v1/documents/copies", {}, auth=client_headers, expected=(403,))
    call("GET", "/api/v1/admin/clients/audit", auth=client_headers, expected=(403,))
    call("GET", "/api/v1/templates", auth={"X-Client-Id": client_id,
        "X-Internal-Api-Key": "invalid"}, expected=(401,))

    template = "/api/v1/templates/smoke"
    call("POST", template, {"html": "<p>Hola {{name}}</p>",
        "text": "Hola {{name}}", "requiredVariables": ["name"]},
        auth=client_headers, expected=(201,))
    call("POST", template + "/versions/1/publish", auth=client_headers)
    preview = call("POST", template + "/versions/1/preview",
        {"name": "<b>Prueba</b>"}, auth=client_headers)
    assert "&lt;b&gt;Prueba&lt;/b&gt;" in preview["html"]
    call("POST", template + "/versions", {"html": "<p>Bienvenido {{name}}</p>",
        "text": "Bienvenido {{name}}", "requiredVariables": ["name"]},
        auth=client_headers, expected=(201,))
    call("POST", template + "/versions/2/publish", auth=client_headers)

    future = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=3)).isoformat()
    payload = {"subject": "Scheduled " + suffix, "templateKey": "custom/smoke",
        "variables": {"name": "Ana"}, "recipients": [{"email": "scheduled-" + suffix + "@example.test",
        "type": "TO"}], "scheduledAt": future}
    idem = "scheduled-" + suffix
    queued = call("POST", "/api/v1/emails", payload, client_headers,
        {"Idempotency-Key": idem}, expected=(202,))
    repeated = call("POST", "/api/v1/emails", payload, client_headers,
        {"Idempotency-Key": idem}, expected=(202,))
    assert queued["id"] == repeated["id"], "scheduled request lost idempotency"
    call("GET", "/api/v1/emails/" + queued["id"],
        auth=legacy_headers, expected=(404,))
    cancelled = call("DELETE", "/api/v1/emails/" + queued["id"], auth=client_headers)
    assert cancelled["status"] == "CANCELLED"
    status = call("GET", "/api/v1/emails/" + queued["id"], auth=client_headers)
    assert status["status"] == "CANCELLED"

    recipients = [{"email": f"campaign-{suffix}-{i}@example.test",
        "variables": {"name": f"Cliente {i}"}} for i in range(101)]
    staged = call("POST", "/api/v1/campaigns", {
        "subject": "Campaign " + suffix, "templateKey": "custom/smoke",
        "recipients": recipients, "commonVariables": {},
    }, auth=client_headers, expected=(202,))
    batch_id = staged["batchId"]
    assert staged["stagedRecipients"] == 101
    wait_until("the 101 recipients to leave staging", lambda:
        call("GET", "/api/v1/campaigns/" + batch_id,
            auth=client_headers)["stagedRemaining"] == 0)
    info = call("GET", "/api/v1/campaigns/" + batch_id, auth=client_headers)
    assert info["stagedRemaining"] == 0
    call("DELETE", "/api/v1/campaigns/" + batch_id, auth=client_headers)

    # CSV marketing import: explicit consent, quoted commas and variable mapping.
    csv_data = ("email,display_name,consent,var_name\r\n"
                f"csv-{suffix}-1@example.test,\"Persona, Uno\",true,Ana\r\n"
                f"csv-{suffix}-2@example.test,Persona Dos,true,Beto\r\n").encode("utf-8")
    metadata = json.dumps({"subject": "CSV " + suffix, "templateKey": "custom/smoke",
                           "purpose": "MARKETING"}).encode("utf-8")
    boundary = "mail-platform-" + suffix
    multipart = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
                 "Content-Type: application/json\r\n\r\n").encode() + metadata +                 (f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                 "filename=\"recipients.csv\"\r\nContent-Type: text/csv\r\n\r\n").encode() +                 csv_data + (f"\r\n--{boundary}--\r\n").encode()
    request = Request(API + "/api/v1/campaigns/import-csv", data=multipart, method="POST",
                      headers={**client_headers, "Content-Type": "multipart/form-data; boundary=" + boundary})
    with urlopen(request, timeout=30) as response:
        assert response.status == 202
        imported = json.loads(response.read())
    assert imported["stagedRecipients"] == 2
    csv_batch = imported["batchId"]
    wait_until("CSV recipients to leave staging", lambda:
        call("GET", "/api/v1/campaigns/" + csv_batch,
             auth=client_headers)["stagedRemaining"] == 0)

    # Tenant-isolated opt-out: suppress before staging, and after staging.
    optout = f"optout-{suffix}@example.test"
    eligible = f"eligible-{suffix}@example.test"
    call("POST", "/api/v1/suppressions", {"email": optout, "reason": "UNSUBSCRIBED"},
         auth=client_headers, expected=(201,))
    assert any(entry["email"] == optout for entry in call(
        "GET", "/api/v1/suppressions", auth=client_headers))
    assert all(entry["email"] != optout for entry in call(
        "GET", "/api/v1/suppressions", auth=legacy_headers))
    call("POST", "/api/v1/campaigns", {
        "subject": "Missing consent", "templateKey": "custom/smoke", "purpose": "MARKETING",
        "recipients": [{"email": eligible, "consent": False, "variables": {"name": "Ana"}}],
    }, auth=client_headers, expected=(400,))
    marketing = call("POST", "/api/v1/campaigns", {
        "subject": "Marketing " + suffix, "templateKey": "custom/smoke", "purpose": "MARKETING",
        "recipients": [
            {"email": optout, "consent": True, "variables": {"name": "Opt-out"}},
            {"email": eligible, "consent": True, "variables": {"name": "Eligible"}},
        ],
    }, auth=client_headers, expected=(202,))
    assert marketing["suppressedRecipients"] == 1
    assert marketing["stagedRecipients"] == 1
    assert call("GET", "/api/v1/campaigns/"+marketing["batchId"],
                auth=client_headers)["suppressedRecipients"] == 1
    call("DELETE", "/api/v1/campaigns/"+marketing["batchId"], auth=client_headers)
    call("DELETE", "/api/v1/suppressions?email="+optout, auth=client_headers,
         expected=(204,))

    # A real marketing email must carry a one-use opt-out link. GET prefetch is read-only.
    address = f"unsubscribe-{suffix}@example.test"
    one_click_address = f"oneclick-{suffix}@example.test"
    new_marketing = call("POST", "/api/v1/campaigns", {
        "subject": "Public opt-out " + suffix,
        "templateKey": "custom/smoke", "purpose": "MARKETING",
        "recipients": [
            {"email": address, "consent": True, "variables": {"name": "Subscriber"}},
            {"email": one_click_address, "consent": True, "variables": {"name": "One Click"}},
        ],
    }, auth=client_headers, expected=(202,))
    assert new_marketing["stagedRecipients"] == 2

    def received_for(recipient):
        with urlopen(MAILPIT + "/api/v1/messages", timeout=8) as response:
            rows = json.loads(response.read()).get("messages", [])
        for row in rows:
            if any(to.get("Address", "").lower() == recipient.lower() for to in row.get("To", [])):
                with urlopen(MAILPIT + "/api/v1/message/" + row["ID"], timeout=8) as response:
                    return json.loads(response.read())
        return None

    marketing_email = wait_until("SMTP marketing email with opt-out link", lambda: received_for(address))
    import re
    match = re.search(r"https?://[^\s<>]+/api/v1/public/unsubscribe\?token=[A-Za-z0-9_-]{43}",
                      marketing_email.get("Text", ""))
    assert match, "marketing SMTP message must contain the unsubscribe link"
    link = match.group(0)
    expected_origin = os.environ["MARKETING_PUBLIC_BASE_URL"].rstrip("/")
    parsed_link = urlsplit(link)
    assert (parsed_link.scheme, parsed_link.netloc) == (
        urlsplit(expected_origin).scheme, urlsplit(expected_origin).netloc)
    public_path = parsed_link.path + "?" + parsed_link.query
    with urlopen(API + public_path, timeout=8) as response:
        assert response.status == 200
    assert not any(entry["email"] == address for entry in call(
        "GET", "/api/v1/suppressions", auth=client_headers)), "GET must not unsubscribe"
    form = urlencode({"token": link.split("token=", 1)[1]}).encode("ascii")
    for _ in range(2):
        req = Request(API + "/api/v1/public/unsubscribe", data=form, method="POST",
                      headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urlopen(req, timeout=8) as response:
            assert response.status == 200
    assert any(entry["email"] == address for entry in call(
        "GET", "/api/v1/suppressions", auth=client_headers)), "public opt-out not persisted"

    # Mailpit captures the actual SMTP envelope/MIME. Verify that the transmitted
    # marketing message contains both RFC 8058 headers, then exercise its POST.
    one_click_mail = wait_until("one-click SMTP message", lambda: received_for(one_click_address))
    with urlopen(MAILPIT + "/api/v1/message/" + one_click_mail["ID"] + "/raw", timeout=8) as response:
        raw = response.read()
    one_click_url, one_click_token = require_one_click(parse_raw(raw), expected_origin)
    one_click_parsed = urlsplit(one_click_url)
    one_click_path = one_click_parsed.path + "?" + one_click_parsed.query
    # GET prefetch must not mutate consent. Invalid POST must not mutate either.
    with urlopen(API + one_click_path, timeout=8) as response:
        assert response.status == 200
    assert all(entry["email"] != one_click_address for entry in call(
        "GET", "/api/v1/suppressions", auth=client_headers))
    malformed = Request(API + one_click_path, data=b"List-Unsubscribe=Not-One-Click",
                        method="POST", headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        urlopen(malformed, timeout=8)
        raise AssertionError("one-click POST without RFC 8058 confirmation was accepted")
    except HTTPError as exc:
        assert exc.code == 400, f"expected 400 on malformed one-click POST, got {exc.code}"
    assert all(entry["email"] != one_click_address for entry in call(
        "GET", "/api/v1/suppressions", auth=client_headers))
    form = urlencode({"List-Unsubscribe": "One-Click"}).encode("ascii")
    for _ in range(2):
        request = Request(API + one_click_path, data=form, method="POST",
                          headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urlopen(request, timeout=8) as response:
            assert response.status == 200
    assert any(entry["email"] == one_click_address for entry in call(
        "GET", "/api/v1/suppressions", auth=client_headers)), "one-click opt-out not persisted"

    audit = call("GET", "/api/v1/admin/clients/audit?limit=200", auth=admin_headers)
    assert any(event["action"] == "CLIENT_CREATED" and event["clientId"] == client_id
               for event in audit), "client creation should have audit evidence"

    keys = call("GET", "/api/v1/admin/clients/" + client_id + "/credentials",
        auth=admin_headers)
    assert any(key["credentialId"] == created["credentialId"] for key in keys)
    replacement = call("POST", "/api/v1/admin/clients/" + client_id + "/credentials",
        {"ttlDays": 1}, auth=admin_headers, expected=(201,))
    assert replacement["apiKey"] != created["apiKey"]
    call("DELETE", "/api/v1/admin/clients/" + client_id + "/credentials/" + created["credentialId"],
        auth=admin_headers, expected=(204,))
    call("GET", "/api/v1/templates", auth=client_headers, expected=(401,))
    call("GET", "/api/v1/templates", auth={"X-Client-Id": client_id,
        "X-Internal-Api-Key": replacement["apiKey"]})
    print("UPGRADE SMOKE OK: clients, rotation, isolation, escaped versioned templates, "
          "idempotent scheduling, cancellation, 101 staged campaign recipients, CSV import, "
          "visible + RFC 8058 one-click opt-out and admin audit")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"UPGRADE SMOKE FAILED: {error}", file=sys.stderr)
        sys.exit(1)
