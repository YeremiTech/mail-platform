#!/usr/bin/env bash
set -euo pipefail
# Only restore into an EXISTING database whose name was explicitly confirmed in an environment variable.
# This command is DESTRUCTIVE and must be run in a maintenance window or against a disposable rehearsal DB.
if [[ $# -ne 2 || ! "$2" =~ ^[A-Za-z][A-Za-z0-9_-]{0,62}$ ]]; then
  echo 'Usage: CONFIRM_RESTORE_DB=DATABASE restore-postgres.sh BACKUP.dump DATABASE' >&2
  exit 2
fi
BACKUP="$1"
DB="$2"
if [[ "${CONFIRM_RESTORE_DB:-}" != "$DB" ]]; then
  echo 'Refusing restore: set CONFIRM_RESTORE_DB to the exact target database name' >&2
  exit 2
fi
[[ -f "$BACKUP" && ! -L "$BACKUP" && -f "${BACKUP}.sha256" ]] || {
  echo 'A backup file and adjacent SHA256 checksum are required' >&2
  exit 2
}
command -v pg_restore >/dev/null || { echo 'pg_restore is required' >&2; exit 2; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required' >&2; exit 2; }
(cd "$(dirname "$BACKUP")" && sha256sum --check --status "$(basename "$BACKUP").sha256") || {
  echo 'Backup checksum mismatch' >&2; exit 1;
}
pg_restore --list "$BACKUP" >/dev/null
# Avoid accidentally overwriting a live installation while clients are actively writing.
if [[ "${ACKNOWLEDGE_WRITES_STOPPED:-}" != 'yes' ]]; then
  echo 'Refusing restore: stop all API/worker writes and set ACKNOWLEDGE_WRITES_STOPPED=yes' >&2
  exit 2
fi
pg_restore --no-password --dbname="$DB" --clean --if-exists --no-owner \
    --no-privileges --single-transaction --exit-on-error "$BACKUP"
RESULT="$(psql --no-password --dbname="$DB" -X -v ON_ERROR_STOP=1 -Atqc \
    "select case when to_regclass('public.mail_message') is not null and to_regclass('public.flyway_schema_history') is not null then 'RESTORED' else 'INCOMPLETE' end")"
[[ "$RESULT" == 'RESTORED' ]] || { echo 'Restore validation failed' >&2; exit 1; }
echo 'Restore finished; run API/SMTP smoke tests before resuming writes.'
