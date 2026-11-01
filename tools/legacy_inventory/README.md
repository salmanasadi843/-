# Legacy inventory validator

This tool validates operator-supplied JSON exports offline. It does not connect to Firebase,
Firestore, PostgreSQL, Android devices, or any server. It never changes input files or
performs account linking, ownership repair, imports, or migration.

## Input format

Pass one JSON file per source database/export. Each file may contain:

- `source_id`, `source_type`, `extracted_at`
- `accounts`: `uid`, `account_id`, `profile_present`, `role_field`
- `classes`: `id`, `owner_uid`, optional `owner_conflict`, `folder_id`, `published`, `visibility`
- `courses`: `id`, `class_id`
- `sessions`: `id`, `class_id`, optional `course_id`
- `folders`: `id`, `owner_uid`, optional `parent_folder_id`
- `record_counts`: aggregate counts for `shared_feed`, `private_backup`, `device_local`

Only structural metadata is accepted. Do not include names, emails, phone numbers, transcripts,
summaries, audio URLs, file paths, credentials, or access tokens.

## Run

Use a separately generated random HMAC key of at least 32 bytes. Keep it outside Git and
outside the output directory.

```sh
export DARSYAR_INVENTORY_HMAC_KEY="$(python -c 'import secrets; print(secrets.token_hex(32))')"
python tools/legacy_inventory/validator.py \
  --input /secure/offline-export/device-a.json \
  --input /secure/offline-export/device-b.json \
  --out /secure/offline-report
```

Outputs: aggregate `inventory.json`, aggregate `inventory.csv`, and a restricted
`exceptions.restricted.json` containing only source pseudonyms and scoped row IDs.
Protect the restricted exception file as sensitive operational metadata.

## Tests

```sh
python -m unittest discover -s tools/legacy_inventory -p 'test_*.py' -v
```

Tests use synthetic data only. A successful run is not authorization to collect production
exports or begin migration. Follow `docs/migration/legacy-inventory-spec.md` and obtain
explicit approval before any operational stage.
