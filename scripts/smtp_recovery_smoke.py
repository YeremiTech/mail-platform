#!/usr/bin/env python3
"""Disposable SMTP-outage exercise; never stops services or accesses remote hosts.

Phases: setup (before stopping Mailpit SMTP); submit (while stopped);
recover (after the SAME disposable Mailpit container has restarted).
No exactly-once claim is made for arbitrary production SMTP relays.
"""
import argparse
import os
from pathlib import Path
import sys
import time
import uuid
from chaos_recovery_smoke import validate_environment, credentials, http, write_state, read_state


def setup(api, state_path):
    run = uuid.uuid4().hex
    slug = 'smtp-outage-' + run[:16]
    headers = credentials()
    http(api, '/api/v1/templates/' + slug, 'POST',
         {'html': '<p>SMTP recovery {{name}}</p>', 'text': 'SMTP recovery {{name}}',
          'requiredVariables': ['name']}, headers, (200, 201))
    http(api, '/api/v1/templates/' + slug + '/versions/1/publish',
         'POST', headers=headers, expected=(200, 201, 204))
    write_state(state_path, {'phase': 'setup', 'run': run, 'slug': slug,
                             'recipient': 'smtp-outage-' + run + '@example.test'})
    print('PASS: SMTP outage fixture prepared. STOP only the disposable Mailpit container.')


def submit(api, state_path, timeout):
    state = read_state(state_path)
    if state['phase'] != 'setup':
        raise ValueError('SMTP outage fixture has already been submitted')
    headers = credentials()
    headers['Idempotency-Key'] = 'smtp-outage-' + state['run']
    mail = {'subject': 'Synthetic SMTP outage ' + state['run'],
            'templateKey': 'custom/' + state['slug'], 'variables': {'name': 'CI'},
            'recipients': [{'email': state['recipient'], 'type': 'TO'}]}
    result = http(api, '/api/v1/emails', 'POST', mail, headers, (202,))
    repeat = http(api, '/api/v1/emails', 'POST', mail, headers, (202,))
    if result['id'] != repeat['id']:
        raise AssertionError('Idempotency failed during SMTP outage')
    state['message_id'] = result['id']
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = http(api, '/api/v1/emails/' + result['id'], headers=credentials())['status']
        attempts = http(api, '/api/v1/emails/' + result['id'] + '/attempts', headers=credentials())
        if status == 'RETRYING' and any(a['status'] == 'FAILED' for a in attempts):
            state['phase'] = 'submitted'
            temp = state_path.with_name(state_path.name + '.new')
            temp.unlink(missing_ok=True)
            write_state(temp, state)
            temp.replace(state_path)
            print('PASS: refused SMTP handoff was recorded and scheduled for retry without losing the request')
            return
        if status in ('FAILED', 'SENT', 'CANCELLED'):
            raise AssertionError('Unexpected terminal/accepted state during SMTP outage: ' + status)
        time.sleep(2)
    raise AssertionError('SMTP worker did not record a temporary delivery failure')


def recover(api, mailpit, state_path, timeout):
    state = read_state(state_path)
    if state['phase'] != 'submitted':
        raise ValueError('Submit while the disposable SMTP relay is stopped first')
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = http(api, '/api/v1/emails/' + state['message_id'], headers=credentials())['status']
        if status == 'SENT':
            break
        if status in ('FAILED', 'CANCELLED'):
            raise AssertionError('Mail failed instead of recovering after SMTP restart')
        time.sleep(2)
    else:
        raise AssertionError('SMTP acceptance did not recover within the deadline')
    matches = 0
    for offset in range(0, 1000, 100):
        page = http(mailpit, f'/api/v1/messages?start={offset}&limit=100')
        messages = page.get('messages', [])
        matches += sum(any(to.get('Address', '').lower() == state['recipient']
                           for to in msg.get('To', [])) for msg in messages)
        if not messages or len(messages) < 100:
            break
    if matches != 1:
        raise AssertionError(f'Expected one captured synthetic SMTP message, got {matches}')
    attempts = http(api, '/api/v1/emails/' + state['message_id'] + '/attempts', headers=credentials())
    failed = sum(a['status'] == 'FAILED' for a in attempts)
    succeeded = sum(a['status'] == 'SUCCEEDED' for a in attempts)
    if failed < 1 or succeeded != 1:
        raise AssertionError(f'Unexpected attempt history: {failed} failed / {succeeded} succeeded')
    state_path.unlink()
    print('PASS: temporary SMTP outage recovered; one Mailpit capture and recorded failure/retry/success')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('setup', 'submit', 'recover'))
    parser.add_argument('--state', type=Path, default=Path('target/smtp-outage.json'))
    parser.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args()
    if not 10 <= args.timeout <= 1800:
        parser.error('timeout must be 10..1800 seconds')
    if os.getenv('SMTP_OUTAGE_APPROVED') != 'yes':
        parser.error('SMTP_OUTAGE_APPROVED=yes is required for disposable CI infrastructure')
    # The existing strict localhost-only validation also requires this opt-in.
    if os.getenv('MAIL_CHAOS_APPROVED') != 'yes':
        parser.error('MAIL_CHAOS_APPROVED=yes is also required')
    api, mailpit = validate_environment()
    if args.phase == 'setup':
        setup(api, args.state)
    elif args.phase == 'submit':
        submit(api, args.state, args.timeout)
    else:
        recover(api, mailpit, args.state, args.timeout)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'SMTP OUTAGE EXERCISE FAILED: {error}', file=sys.stderr)
        raise SystemExit(1)
