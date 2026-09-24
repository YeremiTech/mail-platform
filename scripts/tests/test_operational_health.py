import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_operational_health import verify


class HealthContractTests(unittest.TestCase):
    @patch.dict(os.environ, {"E2E_CLIENT_ID": "inventory", "E2E_API_KEY": "TEST", "ADMIN_API_KEY": "TEST"})
    def test_accepts_secure_health_contract(self):
        def check(path, headers=None):
            if path.startswith("/actuator/health"):
                return 200
            if path.startswith("/api/v1/security-future-endpoint"):
                return 403 if headers else 401
            if not headers:
                return 401
            return 200 if headers["X-Client-Id"] == "admin" else 403
        verify(check,deadline_seconds=0)

    @patch.dict(os.environ, {"E2E_CLIENT_ID": "inventory", "E2E_API_KEY": "TEST", "ADMIN_API_KEY": "TEST"})
    def test_rejects_misconfigured_readiness_or_metrics(self):
        def check(path, headers=None):
            if path == "/actuator/health/readiness":
                return 503
            return 200
        with self.assertRaisesRegex(AssertionError, "readiness"):
            verify(check,deadline_seconds=0)

        def leaky(path, headers=None):
            if path.startswith("/actuator/health"):
                return 200
            return 200  # Metrics accidentally exposed to unauthenticated clients.
        with self.assertRaisesRegex(AssertionError, "unauthenticated"):
            verify(leaky,deadline_seconds=0)

    @patch.dict(os.environ, {"E2E_CLIENT_ID": "inventory", "E2E_API_KEY": "TEST", "ADMIN_API_KEY": "TEST"})
    def test_rejects_unknown_route_authorized_to_wildcard(self):
        def leaky(path, headers=None):
            if path.startswith("/actuator/health"):
                return 200
            if path.startswith("/actuator/"):
                return 401 if not headers else (200 if headers["X-Client-Id"] == "admin" else 403)
            return 401 if not headers else 200
        with self.assertRaisesRegex(AssertionError, "fail-closed"):
            verify(leaky, deadline_seconds=0)
