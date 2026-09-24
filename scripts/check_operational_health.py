#!/usr/bin/env python3
"""Verify public health probes and restricted management access on a LIVE service.

Only tests the deployed instance; a stubbed health response is not certification.
Production must also isolate the management port at the network layer.
"""
import os
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE = os.environ.get("MAIL_API_URL", "http://127.0.0.1:8080").rstrip("/")


def status(path, headers=None):
    try:
        with urlopen(Request(BASE + path, headers=headers or {}), timeout=10) as response:
            return response.status
    except HTTPError as error:
        return error.code


def verify(check=status, deadline_seconds=120):
    # The workflow starts the Spring Boot process asynchronously; readiness can
    # legitimately be unavailable until Flyway, PostgreSQL and RabbitMQ connect.
    deadline = time.monotonic() + deadline_seconds
    while True:
        try:
            ready = check("/actuator/health/readiness")
        except (URLError, TimeoutError, OSError):
            ready = None
        if ready == 200:
            break
        if time.monotonic() >= deadline:
            raise AssertionError(f"/actuator/health/readiness: expected 200, got {ready}")
        time.sleep(2)
    for path in ("/actuator/health", "/actuator/health/liveness"):
        code = check(path)
        if code != 200:
            raise AssertionError(f"{path}: expected 200, got {code}")
    for path in ("/actuator/prometheus", "/actuator/metrics"):
        code = check(path)
        if code != 401:
            raise AssertionError(f"{path}: unauthenticated expected 401, got {code}")
    common = {"X-Client-Id": os.environ["E2E_CLIENT_ID"],
              "X-Internal-Api-Key": os.environ["E2E_API_KEY"]}
    if check("/actuator/prometheus", common) != 403:
        raise AssertionError("legacy wildcard sender unexpectedly accessed private metrics")
    admin = {"X-Client-Id": "admin", "X-Internal-Api-Key": os.environ["ADMIN_API_KEY"]}
    if check("/actuator/prometheus", admin) != 200:
        raise AssertionError("admin could not access operational metrics")
    canary = "/api/v1/security-future-endpoint-not-reviewed"
    if check(canary) != 401 or check(canary, common) != 403 or check(canary, admin) != 403:
        raise AssertionError("unreviewed API route is not fail-closed")
    print("PASS: live probes, restricted metrics and fail-closed unknown API routes")


if __name__ == "__main__":
    verify()
