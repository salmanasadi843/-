# Darsyar Backend portability and recovery runbook

This runbook makes a future move between compatible hosts possible. It does not
perform or authorize a production migration.

## Portability boundaries

- The Ktor service is stateless; durable state belongs in PostgreSQL or a separately
  configured object-storage service.
- PostgreSQL is the system of record. Schema changes are versioned in Flyway files
  under `src/main/resources/db/migration`.
- Hostnames, database endpoints and credentials are environment variables.
- Android and Web must call a configurable HTTPS API base URL; never bake a server IP
  or provider-specific hostname into business logic.
- Future uploaded files must use a storage interface and an S3-compatible backend.
  Persist object keys and metadata, not local filesystem paths. The current backend
  schema stores object keys but no upload implementation has been enabled.
- Authentication provider identifiers are mapped to internal user UUIDs; roles remain
  in the application database.
- Do not add provider-specific database features, serverless triggers, or auth claims
  to product logic without a replaceable adapter and portability test.

## Create a database backup

Use a machine with PostgreSQL client tools installed. Supply credentials through a
secret manager or protected environment, never command-line arguments or Git.

```bash
export PGHOST=127.0.0.1
export PGPORT=5432
export PGDATABASE=darsyar_staging
export PGUSER=darsyar_backup
export PGPASSWORD='read-from-your-secret-manager'
BACKUP_DIR=/secure/off-host-backups ./backend/scripts/backup-postgres.sh
```

The script writes a PostgreSQL custom-format dump and a JSON manifest with SHA-256.
Copy both files to storage outside the source host and encrypt them at rest. Do not
treat a local-only backup as disaster recovery.

## Restore to a new, empty database

Create a new database on the destination first. The restore script verifies the
checksum and refuses to run if the target contains any user tables. It does not drop
or clean existing tables.

```bash
export PGHOST=destination-db.example
export PGPORT=5432
export PGDATABASE=darsyar_restore_test
export PGUSER=darsyar_restore
export PGPASSWORD='read-from-your-secret-manager'
./backend/scripts/restore-postgres.sh /secure/off-host-backups/darsyar-example.dump
```

After restore:
1. Start the same compatible Backend image against the restored database.
2. Verify `/health/ready` and `/api/v1`.
3. Compare row counts, foreign-key integrity and selected record checksums.
4. Test admin/teacher/student permissions and representative Android/Web flows.
5. Keep the old host unchanged until the new environment passes acceptance.

## Future file-storage portability

When audio/document upload is implemented:
- use an S3-compatible object-storage interface;
- configure endpoint, bucket, region and credentials outside source code;
- maintain a manifest of object key, byte size, media type, checksum and owning record;
- copy objects independently from the database and verify checksums after copy;
- keep local device paths out of server records;
- test against a second compatible storage endpoint before production use.

No uploaded-file service or object copy is active in this foundation release.

## Production cutover (future, separately approved)

A real move requires a maintenance/cutover window, a final consistent backup, write
freeze or a proven change-data-capture strategy, restore validation, DNS/TLS switch,
monitoring, and a rollback plan. Do not run two writable production databases without
a designed conflict-resolution protocol. This runbook alone does not authorize a cutover.
