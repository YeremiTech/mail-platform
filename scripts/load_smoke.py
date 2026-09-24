#!/usr/bin/env python3
"""Explicit opt-in: stage a bounded synthetic campaign and measure priority isolation.
Only run against an isolated Mailpit/staging environment; never against production.
"""
import json
import os
import time
import uuid
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError

api=os.getenv('MAIL_API_URL','http://127.0.0.1:8080').rstrip('/')
if os.getenv('LOAD_SMOKE_ENABLED')!='yes':
    raise SystemExit('Set LOAD_SMOKE_ENABLED=yes only on isolated staging with Mailpit')
if urlparse(api).hostname not in {'localhost','127.0.0.1','::1'}:
    raise SystemExit('Load harness is restricted to localhost to prevent accidental production campaigns')
client=os.environ['E2E_CLIENT_ID']
key=os.environ['E2E_API_KEY']
size=int(os.getenv('LOAD_RECIPIENTS','10000'))
assert 1 <= size <= 10000
max_wait=int(os.getenv('LOAD_WAIT_SECONDS','1200'))
assert 10 <= max_wait <= 7200
verify_smtp=os.getenv("LOAD_VERIFY_SMTP", "yes") == "yes"
verify_mailpit=os.getenv("LOAD_VERIFY_MAILPIT", "no") == "yes"
max_transaction_latency=int(os.getenv("LOAD_MAX_TRANSACTION_LATENCY_SECONDS", "90"))
assert 1 <= max_transaction_latency <= 3600
max_campaign_seconds=int(os.getenv('LOAD_MAX_CAMPAIGN_SECONDS','5400'))
assert 30 <= max_campaign_seconds <= 7200
max_heap_mb=int(os.getenv('LOAD_MAX_HEAP_MB','1024'))
assert 128 <= max_heap_mb <= 32768
admin_key=os.getenv('ADMIN_API_KEY','')
peak_heap_bytes=0
run=uuid.uuid4().hex[:12]
headers={'X-Client-Id':client,'X-Internal-Api-Key':key,'Content-Type':'application/json'}

def metric_heap_used():
    global peak_heap_bytes
    if not admin_key:
        return None
    req=Request(api+'/actuator/metrics/jvm.memory.used?tag=area:heap',headers={'X-Client-Id':'admin','X-Internal-Api-Key':admin_key})
    with urlopen(req,timeout=15) as response:
        payload=json.load(response)
    value=sum(float(x.get('value',0)) for x in payload.get('measurements',[]) if x.get('statistic')=='VALUE')
    peak_heap_bytes=max(peak_heap_bytes,int(value))
    if value > max_heap_mb*1024*1024:
        raise SystemExit(f'FAIL: heap usage {value/1024/1024:.1f} MiB exceeds limit {max_heap_mb} MiB')
    return value

def call(method,path,body=None):
    payload=None if body is None else json.dumps(body).encode()
    request=Request(api+path,headers=headers,data=payload,method=method)
    with urlopen(request,timeout=45) as response:
        return json.load(response)

slug='load-'+run
template=f'custom/{slug}'
call('POST',f'/api/v1/templates/{slug}',{'html':'<p>Hello {{{{name}}}}</p>',
    'text':'Hello {{name}}','requiredVariables':['name']})
call('POST',f'/api/v1/templates/{slug}/versions/1/publish')
recipients=[{'email':f'load-{run}-{i}@example.test','variables':{'name':f'Load {i}'}}
            for i in range(size)]
start=time.monotonic()
campaign=call('POST','/api/v1/campaigns',{'subject':'Isolated synthetic load '+run,
    'templateKey':template,'purpose':'TRANSACTIONAL','recipients':recipients})
assert campaign['stagedRecipients']==size
batch_id=campaign['batchId']
print(f'Staged {size} synthetic recipients in {time.monotonic()-start:.3f}s; batch={batch_id}')
# Transactional single sends must still be admitted while bulk recipients are draining.
transaction=call('POST','/api/v1/emails',{'subject':'Transactional during bulk '+run,
    'templateKey':template,'variables':{'name':'Transactional'},
    'recipients':[{'email':f'transaction-{run}@example.test','type':'TO'}]})
assert transaction['status'] in {'CREATED','QUEUED','PROCESSING','SENT'}
transaction_started=time.monotonic()
deadline=time.monotonic()+max_wait
transaction_latency=None
staging_done=None
while time.monotonic()<deadline:
    elapsed=time.monotonic()-start
    if elapsed > max_campaign_seconds:
        raise SystemExit(f'FAIL: campaign exceeded capacity objective of {max_campaign_seconds}s')
    metric_heap_used()
    state=call('GET',f'/api/v1/campaigns/{batch_id}')
    status=call('GET',f'/api/v1/emails/{transaction["id"]}')['status']
    if transaction_latency is None and status=='SENT':
        transaction_latency=round(time.monotonic()-transaction_started,3)
        if transaction_latency>max_transaction_latency:
            raise SystemExit(f'FAIL: transactional SMTP acceptance took {transaction_latency}s '
                             f'while bulk campaign was draining (limit {max_transaction_latency}s)')
    elif status in ('FAILED','CANCELLED'):
        raise SystemExit(f'FAIL: transactional delivery ended as {status}')
    if transaction_latency is None and time.monotonic()-transaction_started>max_transaction_latency:
        raise SystemExit('FAIL: bulk delivery starved transactional SMTP acceptance')
    if state['stagedRemaining']==0 and staging_done is None:
        staging_done=round(elapsed,3)
        print(f'Staging drained in {staging_done}s')
    summary=state.get('summary',{})
    accepted=summary.get('sent',0)
    failed=summary.get('failed',0)
    if failed or summary.get('cancelled',0):
        raise SystemExit(f'FAIL: bulk campaign contains {failed} failed and '
                         f'{summary.get("cancelled",0)} cancelled deliveries')
    if staging_done is not None and transaction_latency is not None:
        if not verify_smtp or (summary.get('messages',0)==size and accepted==size):
            break
    time.sleep(2)
else:
    raise SystemExit(f'FAIL: campaign or transactional message not completed in {max_wait}s')
if transaction_latency is None:
    raise SystemExit('FAIL: transaction not SMTP-accepted')
if verify_smtp:
    print(f'PASS: all {size} campaign messages SMTP-accepted; '
          f'transactional message SMTP-accepted after {transaction_latency}s')
else:
    print(f'PASS: {size} recipients left staging; '
          'SMTP acceptance of the full campaign was NOT verified')


def mailpit_recipients():
    # Mailpit v1 paginates /api/v1/messages. Check the exact synthetic recipient
    # set, not the total Mailpit inbox (other tests may have sent messages).
    mailpit=os.getenv('MAILPIT_URL','http://127.0.0.1:8025').rstrip('/')
    if urlparse(mailpit).hostname not in {'localhost','127.0.0.1','::1'}:
        raise SystemExit('Mailpit validation is restricted to local staging')
    found=set()
    marker=f'load-{run}-'
    start_index=0
    while start_index < size * 2 + 1000:
        with urlopen(f'{mailpit}/api/v1/messages?start={start_index}&limit=100',timeout=20) as response:
            rows=json.load(response).get('messages',[])
        if not rows:
            break
        for row in rows:
            for recipient in row.get('To',[]):
                address=recipient.get('Address','').lower()
                if address.startswith(marker) and address.endswith('@example.test'):
                    found.add(address)
        start_index+=len(rows)
        if len(found)==size:
            break
    return found

metric_heap_used()
if peak_heap_bytes:
    print(f'Peak JVM heap observed: {peak_heap_bytes/1024/1024:.1f} MiB (limit {max_heap_mb} MiB)')

if verify_mailpit:
    found=mailpit_recipients()
    if len(found)!=size:
        raise SystemExit(f'FAIL: Mailpit captured {len(found)}/{size} expected synthetic SMTP recipients')
    print(f'PASS: Mailpit captured all {size} expected SMTP recipient messages')
else:
    print('NOTE: SMTP ACCEPTED is not mailbox delivery. Mailpit full-recipient verification not requested.')
