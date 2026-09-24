#!/usr/bin/env python3
"""Verify that live OpenAPI publishes every REST operation declared by controllers.

The source inventory is the same scope enforced by the security-route guard. This
prevents a new controller method from existing in Spring MVC while silently being
absent from the published integration contract.
"""
from __future__ import annotations
import json, os, sys, urllib.request, xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from check_security_route_contract import routes  # noqa: E402

base=os.environ.get('MAIL_API_URL','http://127.0.0.1:8080').rstrip('/')
admin=os.environ['ADMIN_API_KEY']
request=urllib.request.Request(base+'/v3/api-docs',headers={'X-Client-Id':'admin','X-Internal-Api-Key':admin})
with urllib.request.urlopen(request,timeout=20) as response:
    contract=json.load(response)

ns={'m':'http://maven.apache.org/POM/4.0.0'}
reactor=ET.parse(ROOT/'pom.xml').getroot()
expected_version=reactor.find('m:version',ns).text
actual_version=contract.get('info',{}).get('version')
if actual_version!=expected_version:
    raise SystemExit(f'OpenAPI version mismatch: expected {expected_version}, got {actual_version}')

controllers=ROOT/'mail-api/src/main/java/com/yeremitech/mailplatform/api/controller'
expected=set()
for controller in sorted(controllers.glob('*Controller.java')):
    for method,path in routes(controller.read_text(encoding='utf-8')):
        expected.add((method.lower(),path))
paths=contract.get('paths',{})
missing=[]; malformed=[]
for method,path in sorted(expected):
    op=paths.get(path,{}).get(method)
    if op is None:
        missing.append(f'{method.upper()} {path}')
        continue
    if not op.get('responses'):
        malformed.append(f'{method.upper()} {path}: responses missing')
    if not op.get('operationId'):
        malformed.append(f'{method.upper()} {path}: operationId missing')
if missing:
    raise SystemExit('OpenAPI missing controller operations: '+', '.join(missing))
if malformed:
    raise SystemExit('OpenAPI malformed operations: '+', '.join(malformed))

security=contract.get('components',{}).get('securitySchemes',{})
for ident,expected_name in (('clientId','X-Client-Id'),('clientApiKey','X-Internal-Api-Key')):
    scheme=security.get(ident,{})
    if scheme.get('type')!='apiKey' or scheme.get('in')!='header' or scheme.get('name')!=expected_name:
        raise SystemExit(f'OpenAPI missing mandatory auth header {expected_name}')

# A global security requirement is intentional; public unsubscribe endpoints are
# exercised independently E2E and Spring Security is the authorization source of truth.
if not contract.get('security'):
    raise SystemExit('OpenAPI must document the default API-key security requirement')
print(f'PASS: all {len(expected)} controller REST operations are published with responses, operationId and auth schemes')
