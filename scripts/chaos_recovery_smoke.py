#!/usr/bin/env python3
"""Opt-in, three-phase Outbox recovery exercise for DISPOSABLE localhost services.

setup: create and publish an isolated template. Stop RabbitMQ manually afterwards.
submit: with RabbitMQ stopped and PostgreSQL healthy, assert API accepts durable mail.
recover: restart RabbitMQ manually and assert SMTP acceptance and Mailpit capture.

This script never controls infrastructure and never contains production credentials.
"""
import argparse
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def validate_environment():
    if os.environ.get("MAIL_CHAOS_APPROVED") != "yes":
        raise ValueError("MAIL_CHAOS_APPROVED=yes is required for a disposable test environment")
    api = os.environ.get("MAIL_API_URL", "http://127.0.0.1:8080").rstrip("/")
    mailpit = os.environ.get("MAILPIT_URL", "http://127.0.0.1:8025").rstrip("/")
    for name, url in (("API", api), ("Mailpit", mailpit)):
        parsed = urlparse(url)
        if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError(f"{name} must be an isolated localhost HTTP endpoint")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError(f"{name} URL must not contain credentials or a query")
    return api, mailpit


def http(base, path, method="GET", body=None, headers=None, expected=(200,)):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req_headers = dict(headers or {})
    if data is not None:
        req_headers["Content-Type"] = "application/json"
    request = Request(base + path, data=data, headers=req_headers, method=method)
    try:
        with urlopen(request, timeout=12) as response:
            code, result = response.status, response.read()
    except HTTPError as error:
        code, result = error.code, error.read()
    if code not in expected:
        # Do not print response bodies: they may contain credentials or recovery data.
        raise AssertionError(f"{method} {path}: HTTP {code}, expected {expected}")
    return json.loads(result) if result and result.lstrip().startswith((b"{", b"[")) else None


def credentials():
    return {"X-Client-Id": os.environ["E2E_CLIENT_ID"],
            "X-Internal-Api-Key": os.environ["E2E_API_KEY"]}


def write_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise FileExistsError("A chaos state file already exists; choose a new --state or complete the exercise")
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as out:
        json.dump(state, out, indent=2)
        out.write("\n")


def read_state(path):
    state = json.loads(path.read_text(encoding="utf-8"))
    if state.get("phase") not in ("setup", "submitted") or not re.fullmatch("[a-f0-9]{32}", state.get("run", "")):
        raise ValueError("Invalid or mismatched chaos state")
    return state


def service_status(api, path):
    try:
        with urlopen(api + path, timeout=8) as response:
            return response.status
    except HTTPError as error:
        return error.code


def setup(api, path):
    if service_status(api, "/actuator/health/readiness") != 200:
        raise AssertionError("Start with PostgreSQL and RabbitMQ healthy before the outage exercise")
    run = uuid.uuid4().hex
    slug = "chaos-" + run[:16]
    headers = credentials()
    http(api, "/api/v1/templates/" + slug, "POST",
         {"html": "<p>Recovery {{name}}</p>", "text": "Recovery {{name}}", "requiredVariables": ["name"]},
         headers, (200, 201))
    http(api, "/api/v1/templates/" + slug + "/versions/1/publish", "POST", headers=headers, expected=(200, 201, 204))
    write_state(path, {"phase": "setup", "run": run, "slug": slug,
                       "recipient": "chaos-" + run + "@example.test"})
    print("PASS: synthetic template published. STOP RabbitMQ on the disposable test stack, then run submit.")


def submit(api, path):
    state = read_state(path)
    if state["phase"] != "setup":
        raise ValueError("Email already submitted for this exercise")
    if service_status(api, "/actuator/health/liveness") != 200:
        raise AssertionError("API liveness must remain 200 during broker failure")
    if service_status(api, "/actuator/health/readiness") != 503:
        raise AssertionError("Readiness must report 503 during the controlled RabbitMQ outage")
    headers = credentials()
    headers["Idempotency-Key"] = "chaos-" + state["run"]
    mail = {"subject": "Outbox recovery " + state["run"], "templateKey": "custom/" + state["slug"],
            "variables": {"name": "disposable"}, "recipients": [{"email": state["recipient"], "type": "TO"}]}
    started = time.monotonic()
    result = http(api, "/api/v1/emails", "POST", mail, headers, (202,))
    repeat = http(api, "/api/v1/emails", "POST", mail, headers, (202,))
    if result["id"] != repeat["id"]:
        raise AssertionError("Idempotency failed during RabbitMQ outage")
    state["message_id"] = result["id"]
    state["phase"] = "submitted"
    # Broker publication is asynchronous. The message should not become SENT
    # while the only broker is stopped; do not accept a pre-existing duplicate.
    time.sleep(4)
    status = http(api, "/api/v1/emails/" + result["id"], headers=credentials())["status"]
    if status == "SENT":
        raise AssertionError("Message was SMTP-accepted; confirm RabbitMQ was truly stopped before submit")
    if status in ("FAILED", "CANCELLED"):
        raise AssertionError("Durable request failed while the broker was unavailable")
    # Replace via an atomic file swap only after proving acceptance and idempotency.
    temp = path.with_name(path.name + ".new")
    temp.unlink(missing_ok=True)
    write_state(temp, state)
    temp.replace(path)
    print(f"PASS: API accepted an idempotent message during RabbitMQ outage in {time.monotonic()-started:.2f}s."
          " RESTART RabbitMQ, then run recover.")


def recover(api, mailpit, path, timeout):
    state = read_state(path)
    if state["phase"] != "submitted":
        raise ValueError("Run submit while RabbitMQ is stopped before recovery")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and service_status(api, "/actuator/health/readiness") != 200:
        time.sleep(2)
    if service_status(api, "/actuator/health/readiness") != 200:
        raise AssertionError("Readiness did not recover after RabbitMQ restart")
    while time.monotonic() < deadline:
        status = http(api, "/api/v1/emails/" + state["message_id"], headers=credentials())["status"]
        if status == "SENT":
            break
        if status in ("FAILED", "CANCELLED"):
            raise AssertionError("Message failed instead of recovering after broker restart")
        time.sleep(2)
    else:
        raise AssertionError("Outbox message was not SMTP-accepted within the recovery deadline")
    # A persisted SENT record alone cannot prove SMTP capture by the test relay.
    # Mailpit's message list is paginated and shared with other test cases.
    matched = False
    for offset in range(0, 1000, 100):
        rows = http(mailpit, f"/api/v1/messages?start={offset}&limit=100").get("messages", [])
        if not rows:
            break
        for mail in rows:
            if any(recipient.get("Address", "").lower() == state["recipient"]
                   for recipient in mail.get("To", [])):
                matched = True
                break
        if matched:
            break
    if not matched:
        raise AssertionError("SMTP accepted state exists, but Mailpit did not capture the synthetic message")
    attempts = http(api, "/api/v1/emails/" + state["message_id"] + "/attempts", headers=credentials())
    successful = sum(1 for item in attempts if item.get("status") == "SUCCEEDED")
    if successful != 1:
        raise AssertionError(f"Expected one recorded SMTP success, got {successful}; investigate duplicates")
    path.unlink()
    print("PASS: durable Outbox recovered after RabbitMQ restart, one SMTP success and Mailpit receipt")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("setup", "submit", "recover"))
    parser.add_argument("--state", type=Path, default=Path("target/chaos-run.json"))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not 10 <= args.timeout <= 1800:
        parser.error("timeout must be 10..1800 seconds")
    api, mailpit = validate_environment()
    if args.phase == "setup":
        setup(api, args.state)
    elif args.phase == "submit":
        submit(api, args.state)
    else:
        recover(api, mailpit, args.state, args.timeout)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"CHAOS TEST FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
