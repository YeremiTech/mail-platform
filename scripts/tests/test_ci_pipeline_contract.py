"""Guard the fail-closed CI workflow against accidental relaxation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CiPipelineContractTests(unittest.TestCase):
    def test_required_ci_gates_and_external_security_scan(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        mandatory = (
            'java-version: \'25\'',
            'bash ./mvnw -B clean verify',
            'python3 scripts/check_test_results.py',
            'python3 scripts/check_coverage_reports.py',
            'python3 scripts/check_security_route_contract.py',
            'python3 scripts/check_operational_health.py',
            'python3 scripts/check_openapi_contract.py',
            'python3 scripts/e2e_smoke.py',
            'python3 scripts/upgrade_smoke.py',
            'bash scripts/verify_pg_restore.sh',
            'python3 scripts/generate_ci_evidence.py',
            'needs: [verify, dependency-security]',
        )
        for line in mandatory:
            with self.subTest(required=line):
                self.assertIn(line, workflow)
        self.assertIn('org.owasp:dependency-check-maven:', workflow)

    def test_fault_injection_is_opt_in_and_never_runs_on_push(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        for toggle in ('run_load', 'run_ha', 'run_chaos', 'run_smtp_chaos'):
            self.assertIn(f'{toggle}:', workflow)
            self.assertIn(f'inputs.{toggle} == true', workflow)
        self.assertIn("github.event_name == 'schedule'", workflow)
        self.assertIn("test \"${#broker_ids[@]}\" -eq 1", workflow)
        self.assertIn("test \"${#smtp_ids[@]}\" -eq 1", workflow)
        self.assertIn('MAIL_CHAOS_APPROVED:', workflow)
        self.assertIn('SMTP_OUTAGE_APPROVED:', workflow)
        self.assertIn('LOAD_SMOKE_ENABLED:', workflow)

    def test_ci_markers_are_separate_from_maven_clean_output(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn('mkdir -p .ci-evidence', workflow)
        self.assertIn('target/ci-evidence/**', workflow)
        self.assertIn("test \"$(find verification -name 'verification-summary.json' -type f | wc -l)\" -eq 1", workflow)
        self.assertIn('.ci-evidence/', (ROOT / '.gitignore').read_text())


if __name__ == '__main__':
    unittest.main()
