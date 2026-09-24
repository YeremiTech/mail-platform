#!/usr/bin/env python3
"""Release guard: prevent stale JAR references, conflicting reactor versions or Flyway gaps."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=ET.parse(root/'pom.xml').getroot()
version=pom.find('m:version',ns).text
assert version == '0.10.6-SNAPSHOT',f'Unexpected reactor version {version}'
modules=[m.text for m in pom.findall('m:modules/m:module',ns)]
assert len(modules)==8,len(modules)
for module in modules:
    child=ET.parse(root/module/'pom.xml').getroot()
    assert child.find('m:parent/m:version',ns).text==version, f'Stale parent in {module}'
ci=(root/'.github/workflows/ci.yml').read_text()
assert 'mail-api-0.8.0-SNAPSHOT.jar' not in ci, 'CI still references old API JAR'
assert 'MAIL_API_JAR' in ci, 'CI must use dynamically resolved artifact'
assert version in (root/'PROJECT_STATUS.md').read_text(), 'stale project status'
openapi = (root/'mail-api/src/main/java/com/yeremitech/mailplatform/api/config/OpenApiConfiguration.java').read_text()
assert '.version(artifactVersion)' in openapi, 'OpenAPI must use filtered reactor version'
api_pom = (root/'mail-api/pom.xml').read_text()
assert 'mail-platform-version.properties' in api_pom and '<filtering>true</filtering>' in api_pom
assert (root/'mail-api/src/main/resources/mail-platform-version.properties').read_text().strip() == 'app.api.version=@project.version@'

migrations=list((root/'mail-infrastructure/src/main/resources/db/migration').glob('V*__*.sql'))
numbers=sorted(int(re.search(r'^V(\d+)__',p.name).group(1)) for p in migrations)
assert numbers==list(range(1,18)),f'Missing/duplicate Flyway versions: {numbers}'
print(f'PASS: {len(modules)} modules at {version}; Flyway V1..V17; release CI references checked')
