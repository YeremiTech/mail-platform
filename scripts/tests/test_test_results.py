import sys
import tempfile
from pathlib import Path
from unittest import TestCase
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_test_results


class TestResultGuardTests(TestCase):
    def test_missing_report_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(check_test_results, "REQUIRED", {"m": ["CriticalTest"]}):
                with self.assertRaisesRegex(AssertionError, "no JUnit XML"):
                    check_test_results.validate_results(Path(folder))

    def test_skipped_and_failed_tests_cannot_pass(self):
        for marker in ("<skipped/>", "<failure/>", "<error/>"):
            with tempfile.TemporaryDirectory() as folder:
                path = Path(folder) / "m/target/surefire-reports"
                path.mkdir(parents=True)
                (path / "TEST-critical.xml").write_text(
                    f'<testsuite><testcase classname="demo.CriticalTest" name="real">{marker}</testcase></testsuite>')
                with patch.object(check_test_results, "REQUIRED", {"m": ["CriticalTest"]}):
                    with self.assertRaisesRegex(AssertionError, "critical test skipped/failed"):
                        check_test_results.validate_results(Path(folder))

    def test_rejects_unexecuted_concurrency_and_worker_suites(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for module, name in (("mail-api", "FlywayMigrationIntegrationTest"),
                                 ("mail-worker", "MailDeliveryWorkerTest")):
                path = root / module / "target/surefire-reports"
                path.mkdir(parents=True)
                (path / "TEST-executed.xml").write_text(
                    f'<testsuite><testcase classname="demo.{name}" name="passes"/></testsuite>')
            with patch.object(check_test_results, "REQUIRED", {
                    "mail-api": ["FlywayMigrationIntegrationTest", "PasswordRecoveryConcurrencyIntegrationTest"],
                    "mail-worker": ["MailDeliveryWorkerTest"]}):
                with self.assertRaisesRegex(AssertionError, "PasswordRecoveryConcurrencyIntegrationTest"):
                    check_test_results.validate_results(root)
