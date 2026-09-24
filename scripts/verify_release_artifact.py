#!/usr/bin/env python3
"""Fail closed on incomplete, unsafe or polluted source release archives.

It checks inclusion/consistency, not binary correctness or integration success.
"""
from pathlib import Path
import argparse
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

REQUIRED = {
    'pom.xml', 'README.md', 'CHANGELOG.md', 'PROJECT_STATUS.md',
    'AUDIT_REPORT.md', 'VALIDATION_REPORT.md',
    'docs/CERTIFICACION_V0105_ES.md',
    '.github/workflows/ci.yml',
    'scripts/check_coverage_reports.py', 'scripts/check_test_results.py',
    'scripts/check_security_route_contract.py', 'scripts/generate_ci_evidence.py',
    'scripts/check_openapi_contract.py', 'scripts/check_operational_health.py',
    'scripts/chaos_recovery_smoke.py', 'scripts/multi_instance_smoke.py',
    'scripts/smtp_recovery_smoke.py',
    'scripts/load_smoke.py', 'scripts/verify_pg_restore.sh',
    'mail-api/src/main/resources/mail-platform-version.properties',
}


def verify(archive):
    with ZipFile(archive) as z:
        corrupted = z.testzip()
        if corrupted:
            raise AssertionError('ZIP CRC failed for: ' + corrupted)
        all_names = z.namelist()
        if len(all_names) != len(set(all_names)):
            raise AssertionError('duplicate archive entries')
        names = set()
        for member in all_names:
            parts = Path(member).parts
            if not parts or parts[0] != 'mail-platform' or '..' in parts or Path(member).is_absolute():
                raise AssertionError('unsafe or unexpected archive path: ' + member)
            if any(part in ('__pycache__', '.git', 'target', '.ci-evidence', 'node_modules') or part.endswith('.pyc') for part in parts):
                raise AssertionError('build residue inside source release: ' + member)
            if not member.endswith('/'):
                names.add('/'.join(parts[1:]))
        missing = REQUIRED - names
        if missing:
            raise AssertionError('required release files absent: ' + ', '.join(sorted(missing)))
        root = ET.fromstring(z.read('mail-platform/pom.xml'))
        namespace = {'p': 'http://maven.apache.org/POM/4.0.0'}
        version = root.findtext('p:version', namespaces=namespace)
        if version != '0.10.6-SNAPSHOT':
            raise AssertionError('wrong release reactor version: ' + str(version))
        modules = [m.text for m in root.findall('p:modules/p:module', namespace)]
        if len(modules) != 8:
            raise AssertionError(f'expected 8 modules; got {modules}')
        for module in modules:
            if f'{module}/pom.xml' not in names:
                raise AssertionError(f'missing POM for {module}')
            source = ET.fromstring(z.read(f'mail-platform/{module}/pom.xml'))
            if source.findtext('p:parent/p:version', namespaces=namespace) != version:
                raise AssertionError(f'stale child POM version in {module}')
        migrations = [int(re.search(r'V(\d+)__', member).group(1)) for member in names
                      if member.startswith('mail-infrastructure/src/main/resources/db/migration/V')
                      and member.endswith('.sql')]
        if sorted(migrations) != list(range(1,18)):
            raise AssertionError('Flyway V1–V17 incomplete or duplicated')
        print(f'PASS: ZIP CRC, paths and files; {version}, {len(modules)} modules, Flyway V1–V17, no residues')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    verify(parser.parse_args().archive)
