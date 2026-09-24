#!/usr/bin/env bash
set -euo pipefail
# PGHOST, PGPORT, PGUSER, PGPASSWORD or PGPASSFILE are read by PostgreSQL client tools.
# Arguments: TARGET_DATABASE DESTINATION_DIRECTORY
if [[ $# -ne 2 || ! "$1" =~ ^[A-Za-z][A-Za-z0-9_-]{0,62}$ ]]; then
  echo 'Usage: backup-postgres.sh DATABASE DESTINATION_DIRECTORY' >&2
  exit 2
fi
command -v pg_dump >/dev/null || { echo 'pg_dump is required' >&2; exit 2; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required' >&2; exit 2; }
DB="$1"
DIR="$2"
umask 077
mkdir -p -- "$DIR"
[[ -d "$DIR" && ! -L "$DIR" ]] || { echo 'Destination must be a directory, not a symlink' >&2; exit 2; }
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$DIR/${DB}-${STAMP}.dump"
if [[ -e "$OUT" ]]; then echo 'Backup already exists' >&2; exit 2; fi
TMP="$(mktemp "${OUT}.partial.XXXXXX")"
trap 'rm -f -- "$TMP"' EXIT
pg_dump --no-password --blobs --format=custom --compress=6 --no-owner --no-privileges \
    --dbname="$DB" --file="$TMP"
[[ -s "$TMP" ]] || { echo 'Backup is empty' >&2; exit 1; }
pg_restore --list "$TMP" >/dev/null
chmod 0600 "$TMP"
mv -- "$TMP" "$OUT"
(cd "$DIR" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
chmod 0600 "${OUT}.sha256"
echo "Backup and checksum saved: $OUT"
