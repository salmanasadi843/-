#!/usr/bin/env bash
set -Eeuo pipefail

DUMP_FILE="${1:?Usage: restore-postgres.sh /path/to/backup.dump}"
: "${PGHOST:?Set PGHOST}"
: "${PGDATABASE:?Set PGDATABASE}"
: "${PGUSER:?Set PGUSER}"
: "${PGPASSWORD:?Set PGPASSWORD}"

if [[ ! -f "$DUMP_FILE" ]]; then
  echo "Backup file not found: $DUMP_FILE" >&2
  exit 2
fi

MANIFEST_FILE="$DUMP_FILE.manifest.json"
if [[ ! -f "$MANIFEST_FILE" ]]; then
  echo "Manifest is missing: $MANIFEST_FILE" >&2
  exit 2
fi

EXPECTED="$(sed -n 's/.*"sha256": "\([a-fA-F0-9]*\)".*/\1/p' "$MANIFEST_FILE")"
ACTUAL="$(sha256sum "$DUMP_FILE" | awk '{print $1}')"
if [[ -z "$EXPECTED" || "$EXPECTED" != "$ACTUAL" ]]; then
  echo "Backup checksum validation failed; refusing restore." >&2
  exit 3
fi

TABLE_COUNT="$(psql -X -v ON_ERROR_STOP=1 -Atqc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema') AND table_type='BASE TABLE'")"
if [[ "$TABLE_COUNT" != "0" ]]; then
  echo "Target database is not empty ($TABLE_COUNT tables). Restore is refused to prevent overwriting data." >&2
  echo "Create a new empty database and retry. This script never drops or cleans tables." >&2
  exit 4
fi

pg_restore --no-owner --no-acl --exit-on-error --single-transaction \
  --dbname="$PGDATABASE" "$DUMP_FILE"

echo "Restore completed into the previously empty database: $PGDATABASE"
echo "Run application health checks and data-count validation before switching traffic."
