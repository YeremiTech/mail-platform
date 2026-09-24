#!/usr/bin/env bash
set -euo pipefail
# Restore verification requires PG* environment variables and a database CREATE privilege.
# Never point at production without an approved backup/restore exercise.
if [[ "${RESTORE_VERIFY_APPROVED:-}" != "yes" ]]; then
  echo 'Set RESTORE_VERIFY_APPROVED=yes after approving an isolated restore exercise' >&2
  exit 2
fi
for executable in pg_dump pg_restore createdb dropdb psql; do
  command -v "$executable" >/dev/null || { echo "Missing PostgreSQL tool: $executable" >&2; exit 2; }
done
: "${PGDATABASE:?Set source PGDATABASE}"
if [[ ! "$PGDATABASE" =~ ^[a-zA-Z0-9_]+$ ]]; then echo 'Unsafe database name' >&2; exit 2; fi
TARGET="mail_restore_verify_$(date -u +%s)_$$"
BACKUP="$(mktemp --suffix=.dump)"
cleanup(){
  rm -f "$BACKUP"
  if [[ "${CREATED:-no}" == yes ]]; then PGDATABASE=postgres dropdb --if-exists "$TARGET" || true; fi
}
trap cleanup EXIT
SOURCE="$PGDATABASE"
echo 'Creating isolated custom-format database backup'
pg_dump --format=custom --blobs --no-owner --no-acl --file="$BACKUP" "$SOURCE"
PGDATABASE=postgres createdb "$TARGET"
CREATED=yes
PGDATABASE="$TARGET" pg_restore --no-owner --no-acl --exit-on-error --dbname="$TARGET" "$BACKUP"
for table in mail_message mail_attachment mail_api_client; do
  source_count="$(PGDATABASE="$SOURCE" psql -X -At -v ON_ERROR_STOP=1 -c "select count(*) from $table")"
  restored_count="$(PGDATABASE="$TARGET" psql -X -At -v ON_ERROR_STOP=1 -c "select count(*) from $table")"
  [[ "$source_count" == "$restored_count" ]] || { echo "FAILED: count mismatch in $table" >&2; exit 1; }
  echo "PASS: $table row count matches: $restored_count"
done
source_version="$(PGDATABASE="$SOURCE" psql -X -At -v ON_ERROR_STOP=1 -c "select version from flyway_schema_history where success=true and version is not null order by installed_rank desc limit 1")"
restored_version="$(PGDATABASE="$TARGET" psql -X -At -v ON_ERROR_STOP=1 -c "select version from flyway_schema_history where success=true and version is not null order by installed_rank desc limit 1")"
[[ "$source_version" == "$restored_version" ]] || { echo 'FAILED: Flyway version differs' >&2; exit 1; }
echo "PASS: restored Flyway version $restored_version"
# A custom-format backup must preserve Large Object bytes, not just attachment metadata.
source_missing="$(PGDATABASE="$SOURCE" psql -X -At -v ON_ERROR_STOP=1 -c 'select count(*) from mail_attachment where content_oid is null')"
restored_missing="$(PGDATABASE="$TARGET" psql -X -At -v ON_ERROR_STOP=1 -c 'select count(*) from mail_attachment where content_oid is null')"
[[ "$source_missing" == 0 && "$restored_missing" == 0 ]] || { echo 'FAILED: null Large Object OIDs' >&2; exit 1; }
source_present="$(PGDATABASE="$SOURCE" psql -X -At -v ON_ERROR_STOP=1 -c 'select count(*) from mail_attachment')"
if [[ "${REQUIRE_BLOB_FIXTURE:-no}" == yes && "$source_present" == 0 ]]; then
  echo 'FAILED: no attachment fixture to validate Large Object restore' >&2; exit 1
fi
# md5(bytea) is built into PostgreSQL; this fingerprint compares every restored
# attachment payload without copying user attachments to the CI runner.
FINGERPRINT_SQL="select md5(coalesce(string_agg(id::text || ':' || md5(lo_get(content_oid)), ',' order by id),'')) from mail_attachment"
source_fingerprint="$(PGDATABASE="$SOURCE" psql -X -At -v ON_ERROR_STOP=1 -c "$FINGERPRINT_SQL")"
restored_fingerprint="$(PGDATABASE="$TARGET" psql -X -At -v ON_ERROR_STOP=1 -c "$FINGERPRINT_SQL")"
[[ "$source_fingerprint" == "$restored_fingerprint" ]] || { echo 'FAILED: restored Large Object bytes differ' >&2; exit 1; }
echo "PASS: all $source_present attachment payloads identical after PostgreSQL backup/restore"
echo "PASS: isolated restore target validated and scheduled for removal"
