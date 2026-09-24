#!/usr/bin/env python3
"""Opt-in multi-instance idempotency and delivery exercise on disposable localhost nodes.

Runs overlapping requests through two instances sharing one PostgreSQL database and
one RabbitMQ broker. Tests acceptance, same public ID, only one persisted SMTP
success and exact Mailpit recipient capture. It cannot guarantee exactly-once SMTP
when provider acknowledgments are lost, and is not an HA production certification.
"""
import json
import os
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def base_urls():
    if os.environ.get('HA_TEST_APPROVED') != 'yes':
        raise ValueError('HA_TEST_APPROVED=yes required for disposable multi-instance tests')
    urls=[s.strip().rstrip('/') for s in os.environ.get('HA_API_URLS', 'http://127.0.0.1:8080,http://127.0.0.1:8081').split(',')]
    if len(urls)!=2 or urls[0]==urls[1]:
        raise ValueError('exactly two distinct local API URLs are required')
    for url in urls:
        parsed=urlparse(url)
        if parsed.scheme!='http' or parsed.hostname not in {'localhost','127.0.0.1','::1'} or parsed.query or parsed.fragment:
            raise ValueError('multi-instance test refuses non-local targets')
    return urls


def call(base, path, method='GET', body=None, headers=None, expected=(200,)):
    payload=None if body is None else json.dumps(body).encode('utf-8')
    h=dict(headers or {})
    if payload is not None: h['Content-Type']='application/json'
    try:
        with urlopen(Request(base+path, data=payload, headers=h, method=method),timeout=15) as reply:
            status,raw=reply.status,reply.read()
    except HTTPError as ex:
        status,raw=ex.code,ex.read()
    if status not in expected: raise AssertionError(f'{method} {path}: HTTP {status} (expected {expected})')
    return json.loads(raw) if raw else None


def wait_ready(base):
    until=time.monotonic()+120
    while time.monotonic()<until:
        try:
            if call(base,'/actuator/health/readiness')['status']=='UP': return
        except (URLError,AssertionError,KeyError,ValueError): pass
        time.sleep(2)
    raise AssertionError('multi-instance node did not become ready')


def mailpit_count(address):
    mailpit=os.environ.get('MAILPIT_URL','http://127.0.0.1:8025').rstrip('/')
    parsed=urlparse(mailpit)
    if parsed.scheme!='http' or parsed.hostname not in {'127.0.0.1','localhost','::1'}:
        raise ValueError('Mailpit must be local')
    count=0
    for start in range(0,1000,100):
        with urlopen(f'{mailpit}/api/v1/messages?start={start}&limit=100',timeout=20) as reply:
            rows=json.load(reply).get('messages',[])
        if not rows: break
        count+=sum(any(r.get('Address','').lower()==address for r in item.get('To',[])) for item in rows)
    return count


def run():
    first,second=base_urls()
    wait_ready(first);wait_ready(second)
    headers={'X-Client-Id':os.environ['E2E_CLIENT_ID'],
             'X-Internal-Api-Key':os.environ['E2E_API_KEY']}
    token=uuid.uuid4().hex
    slug='ha-'+token[:16]
    call(first,'/api/v1/templates/'+slug,'POST',
         {'html':'<p>HA {{name}}</p>','text':'HA {{name}}','requiredVariables':['name']},headers,(201,))
    call(second,'/api/v1/templates/'+slug+'/versions/1/publish','POST',headers=headers)
    recipient='ha-'+token+'@example.test'
    data={'subject':'Concurrent HA '+token,'templateKey':'custom/'+slug,
          'variables':{'name':'test'},'recipients':[{'email':recipient,'type':'TO'}]}
    request_headers={**headers,'Idempotency-Key':'ha-'+token}
    targets=[first,second]*4
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures=[pool.submit(call,target,'/api/v1/emails','POST',data,request_headers,(202,)) for target in targets]
        results=[f.result(timeout=45) for f in futures]
    ids={result['id'] for result in results}
    if len(ids)!=1: raise AssertionError(f'concurrent idempotency created {len(ids)} public message IDs')
    message_id=ids.pop()
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        state=call(second,'/api/v1/emails/'+message_id,headers=headers)['status']
        if state=='SENT': break
        if state in {'FAILED','CANCELLED'}: raise AssertionError('shared Outbox delivery failed')
        time.sleep(2)
    else: raise AssertionError('shared Outbox was not SMTP-accepted')
    attempts=call(first,'/api/v1/emails/'+message_id+'/attempts',headers=headers)
    if sum(a.get('status')=='SUCCEEDED' for a in attempts)!=1:
        raise AssertionError('expected one persisted successful SMTP handoff; investigate worker concurrency')
    copies=mailpit_count(recipient)
    if copies!=1: raise AssertionError(f'expected one Mailpit message; found {copies}')
    print('PASS: two nodes, eight concurrent requests, one idempotent message ID, one SMTP success and one Mailpit receipt')


if __name__=='__main__':
    run()
