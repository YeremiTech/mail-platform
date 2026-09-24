#!/usr/bin/env python3
"""Pre-migrate V16 BYTEA attachments to PostgreSQL Large Objects in bounded batches.

Run only after V16 and before Flyway applies V17. This tool exists for installations
with large historical attachment sets, so V17 can finish quickly without one large
conversion transaction. It is idempotent and never drops columns or changes Flyway
history.
"""
from __future__ import annotations
import argparse, os, subprocess, sys, time


def env(name: str, default: str | None = None) -> str:
    value=os.getenv(name, default)
    if not value:
        raise SystemExit(f"Missing {name}")
    return value


def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument('--batch-size', type=int, default=int(os.getenv('ATTACHMENT_MIGRATION_BATCH_SIZE','250')))
    ap.add_argument('--pause-ms', type=int, default=int(os.getenv('ATTACHMENT_MIGRATION_PAUSE_MS','100')))
    ap.add_argument('--max-batches', type=int, default=int(os.getenv('ATTACHMENT_MIGRATION_MAX_BATCHES','100000')))
    args=ap.parse_args()
    if not 1 <= args.batch_size <= 5000: raise SystemExit('batch-size must be 1..5000')
    if not 0 <= args.pause_ms <= 60000: raise SystemExit('pause-ms must be 0..60000')
    if os.getenv('ATTACHMENT_MIGRATION_APPROVED') != 'yes':
        raise SystemExit('Set ATTACHMENT_MIGRATION_APPROVED=yes after backup and capacity review')
    host=env('PGHOST','localhost'); port=env('PGPORT','5432'); db=env('PGDATABASE','mail_platform'); user=env('PGUSER','mail_platform')
    sql=f"""
DO $$
DECLARE r record; oid_value oid; processed integer := 0;
BEGIN
  FOR r IN
    SELECT id, content FROM mail_attachment
    WHERE content_oid IS NULL AND content IS NOT NULL
    ORDER BY created_at, id
    FOR UPDATE SKIP LOCKED
    LIMIT {args.batch_size}
  LOOP
    oid_value := lo_from_bytea(0, r.content);
    UPDATE mail_attachment
      SET content_oid=oid_value, storage_key='lo:' || oid_value::text
      WHERE id=r.id AND content_oid IS NULL;
    processed := processed + 1;
  END LOOP;
  RAISE NOTICE 'migrated=%', processed;
END $$;
SELECT count(*) FROM mail_attachment WHERE content_oid IS NULL AND content IS NOT NULL;
"""
    for batch in range(1,args.max_batches+1):
        cp=subprocess.run(['psql','-X','-v','ON_ERROR_STOP=1','-h',host,'-p',port,'-U',user,'-d',db,'-At'], input=sql, text=True, capture_output=True)
        if cp.returncode:
            sys.stderr.write(cp.stderr); return cp.returncode
        lines=[x.strip() for x in cp.stdout.splitlines() if x.strip()]
        try: remaining=int(lines[-1])
        except Exception: raise SystemExit('Could not read remaining attachment count from psql output')
        print(f'batch={batch} remaining={remaining}', flush=True)
        if remaining == 0:
            print('PASS: all legacy attachment BYTEA rows have content_oid; Flyway V17 may now run')
            return 0
        time.sleep(args.pause_ms/1000)
    raise SystemExit('max-batches reached before migration completed')

if __name__=='__main__': raise SystemExit(main())
