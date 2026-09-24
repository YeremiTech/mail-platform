import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class ReleaseCalibrationTests(unittest.TestCase):
    def test_container_defaults_to_production_profile(self):
        docker=(ROOT/'Dockerfile').read_text()
        self.assertIn('ENV SPRING_PROFILES_ACTIVE=production',docker)
    def test_large_attachment_upgrade_has_batched_preflight(self):
        code=(ROOT/'scripts/preflight_attachment_migration.py').read_text()
        self.assertIn('FOR UPDATE SKIP LOCKED',code)
        self.assertIn('ATTACHMENT_MIGRATION_APPROVED',code)
        self.assertIn("lo_from_bytea",code)
        self.assertIn('LIMIT {args.batch_size}',code)
    def test_capacity_rehearsal_targets_10000_and_memory_gate(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text()
        harness=(ROOT/'scripts/load_smoke.py').read_text()
        self.assertIn("default: '10000'",workflow)
        self.assertIn("'10000' || inputs.recipients",workflow)
        self.assertIn('LOAD_MAX_HEAP_MB',harness)
        self.assertIn('LOAD_MAX_CAMPAIGN_SECONDS',harness)
    def test_openapi_check_discovers_all_controller_routes(self):
        code=(ROOT/'scripts/check_openapi_contract.py').read_text()
        self.assertIn('for controller in sorted(controllers.glob',code)
        self.assertIn('for method,path in routes(',code)
        self.assertNotIn('required = {',code)
if __name__=='__main__': unittest.main()
