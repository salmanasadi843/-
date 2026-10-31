#!/usr/bin/env bash
set -Eeuo pipefail

: "${PGHOST:?Set PGHOST}"
: "${PGDATABASE:?Set PGDATABASE}"
: "${PGUSER:?Set PGUSER}"
: "${PGPASSWORD:?Set PGPASSWORD}"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
mkdir -p "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="$BACKUP_DIR/darsyar-${PGDATABASE}-$STAMP.dump"
MANIFEST_FILE="$DUMP_FILE.manifest.json"

pg_dump --format=custom --no-owner --no-acl --file="$DUMP_FILE"
CHECKSUM="$(sha256sum "$DUMP_FILE" | awk '{print $1}')"
DB_VERSION="$(psql -X -Atqc 'SHOW server_version')"

cat > "$MANIFEST_FILE" <<EOF
{
  "format": "postgresql-custom",
  "database": "$PGDATABASE",
  "createdAtUtc": "$STAMP",
  "postgresServerVersion": "$DB_VERSION",
  "dumpFile": "$(basename "$DUMP_FILE")",
  "sha256": "$CHECKSUM"
}
EOF

echo "Backup created: $DUMP_FILE"
echo "Manifest created: $MANIFEST_FILE"
echo "Store both files outside the source server. Encrypt backups before off-site storage."
