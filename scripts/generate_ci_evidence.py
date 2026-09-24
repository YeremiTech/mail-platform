#!/usr/bin/env python3
"""Emit fail-closed, non-secret CI validation evidence from real success markers.

Markers are written by the GitHub workflow ONLY after each real command exits 0.
This report establishes a CI run, NOT an external DKIM or production approval.
"""
from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from check_test_results import validate_results
from check_coverage_reports import REQUIRED as COVERAGE_POLICY, validate_report

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_LIVE = frozenset({
    "alert_rules", "runtime_health", "openapi", "smtp_e2e", "client_campaign_e2e", "isolated_restore"
})
OPTIONAL_LIVE = frozenset({"two_instance", "broker_chaos", "smtp_chaos", "synthetic_load"})


def verify_markers(directory: Path, required=REQUIRED_LIVE):
    present = {p.stem for p in directory.glob("*.passed") if p.is_file() and p.read_text().strip() == "PASS"}
    missing = set(required) - present
    if missing:
        raise AssertionError("missing successful live CI checks: " + ", ".join(sorted(missing)))
    return sorted(present & (set(required) | OPTIONAL_LIVE))


def build_evidence(root=ROOT):
    checked = verify_markers(root / ".ci-evidence")
    tests = validate_results(root)
    coverage = {}
    for module, policy in COVERAGE_POLICY.items():
        coverage[module] = validate_report(module, root/module/"target/site/jacoco/jacoco.xml", policy)
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = ET.parse(root/"pom.xml").getroot().find("m:version", namespace).text
    return {
        "artifact_version": version,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "disposable CI services; production deployment and external DKIM not certified",
        "mandatory_live_checks": checked,
        "executed_critical_test_cases": tests,
        "measured_coverage": coverage,
        "remaining_external_checks": ["production-relay DKIM/SPF/DMARC", "production TLS and secrets",
                                       "production-domain DNS", "production capacity and recovery drills"],
    }


if __name__ == "__main__":
    report = build_evidence()
    path = ROOT/"target/ci-evidence/verification-summary.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS: complete live CI evidence saved without secrets; production checks remain separate")
