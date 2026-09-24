#!/usr/bin/env python3
"""Fail CI if critical real-infrastructure integration tests are skipped/absent."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
# A Maven build with only ordinary unit tests must not certify the platform.
# These classes exercise API isolation, concurrency, real PostgreSQL and worker behavior.
REQUIRED = {
    "mail-api": [
        "AttachmentRetentionIntegrationTest",
        "ClientPermissionIntegrationTest",
        "FlywayMigrationIntegrationTest",
        "JdbcLargeObjectAttachmentIntegrationTest",
        "OpenApiConfigurationTest",
        "ProductionConfigurationValidatorTest",
        "RabbitConnectionHealthMetricsTest",
        "MailUpgradeIntegrationTest",
        "PasswordRecoveryConcurrencyIntegrationTest",
        "PostgresFlowIntegrationTest",
        "PublicUnsubscribeIntegrationTest",
        "SuppressionPolicyIntegrationTest",
        "PinnedWebhookTransportTest",
    ],
    "mail-application": ["PasswordRecoveryIsolationTest", "QueueEmailUseCaseTest"],
    "mail-worker": ["MailDeliveryWorkerTest"],
    "mail-infrastructure": ["JdbcAttachmentStorageAdapterTest"],
    "mail-provider-smtp": ["SmtpMailProviderTest"],
}


def validate_results(root=ROOT):
    result = {}
    for module, classes in REQUIRED.items():
        reports = list((root / module / "target/surefire-reports").glob("TEST-*.xml"))
        if not reports:
            raise AssertionError(f"{module}: no JUnit XML reports")
        cases = {}
        for path in reports:
            xml = ET.parse(path).getroot()
            for test in xml.iter("testcase"):
                class_name = test.get("classname", "").rsplit(".", 1)[-1]
                cases.setdefault(class_name, []).append(test)
        for class_name in classes:
            tests = cases.get(class_name, [])
            if not tests:
                raise AssertionError(f"{module}: required test class not executed: {class_name}")
            for test in tests:
                if any(test.find(tag) is not None for tag in ("skipped", "failure", "error")):
                    raise AssertionError(f"{module}: critical test skipped/failed: {class_name}.{test.get('name')}")
            result[f"{module}/{class_name}"] = len(tests)
    return result


if __name__ == "__main__":
    for name, count in validate_results().items():
        print(f"PASS {name}: {count} executed without skip/failure")
