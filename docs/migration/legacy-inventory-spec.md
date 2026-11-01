# Legacy account and content inventory specification

Status: design only; no production extraction or migration is authorized by this document.

## Purpose and safety boundary

Create a reproducible, read-only inventory of legacy Firebase identities and Room/Firestore
teaching records before any account linking or data movement. The first implementation must
run against operator-provided exports or synthetic fixtures, not production credentials.

The inventory is for ownership reconciliation, not content migration. It must never update
Firebase, Room, PostgreSQL, account roles, Firestore documents, or server configuration.

## Inputs

1. Firebase Authentication account export: UID and account state only. Do not ingest email,
   phone, display name, provider secrets, or ID tokens into the report.
2. Firestore profile export for `users/{uid}`: document existence and presence/shape of
   role field only. Do not copy profile values.
3. Firestore `teacher_data/{uid}` metadata and `records` subcollection: record type,
   record count, document path owner UID, and class owner UID where present. Do not read
   transcript bodies, audio URLs, or other content fields.
4. Firestore `shared_materials/{uid}`: document owner UID, declared payload format/version,
   and counts of classes/courses/lectures only. Do not parse or retain full payload content.
5. Operator-generated Room exports from each device/database: row IDs, foreign keys,
   class ownerUid, folder ownerUid, and minimal structural metadata required to validate
   relationships. Exclude transcripts, summaries, audio URLs, file paths, and API keys.

Every input must carry a source label, extraction timestamp, and a cryptographic checksum.
Numeric Room IDs are scoped to one source database and must never be treated as globally
unique.

## Privacy-preserving identifiers

- Keep raw Firebase UIDs and source document paths out of ordinary reports.
- Generate a stable pseudonym using HMAC-SHA-256 with a separately held operator key.
  Do not use a plain hash of UID; UID spaces can be guessed.
- Keep the HMAC key and any reversible mapping file outside Git and outside the report.
- Report internal Room row IDs only together with a source-database pseudonym.
- Do not include email, phone, names, transcript snippets, titles, or audio locations.

## Ownership classification

Each class is assigned exactly one inventory status:

- `matched_uid`: class ownerUid exactly matches one Firebase UID from the account export.
- `blank_owner`: ownerUid is blank and no independent source owner is present. Never assign it to the currently logged-in user.
- `unknown_uid`: nonblank ownerUid has no exact account match.
- `conflicting_owner`: independent source metadata disagrees about the owner, including when one source is blank and another supplies an owner.
- `invalid_record`: required structural fields are malformed.

Courses and sessions inherit the status of their parent class. A missing parent is an orphan;
a duplicated/ambiguous parent ID is reported separately and is not silently classified as an orphan.
Folder ownership is checked independently; a class whose folder belongs to a different owner is
an exception, not an invitation to rewrite IDs.

Shared-feed imports, private teacher backups, and device-local source rows are separate
record populations. Do not collapse them into one population merely because titles,
metadata, or content appear similar.

## Required outputs

Produce aggregate JSON and CSV summaries containing:

- account counts by profile-present/profile-missing and legacy role-field category;
- class counts by ownership status and publication/visibility state;
- course/session counts by inherited ownership status;
- folder counts by exact owner match, blank/unknown owner, and parent/reference validity;
- orphan and ambiguous-parent counts for courses/sessions;
- duplicate numeric IDs only within each individual source database;
- counts of shared-feed versus private-backup records;
- source checksums, extraction times, validator version, and total rows processed.

A separate restricted exception manifest may contain pseudonymous account/source references
and scoped local row IDs needed for human review. It must not contain raw UID, email, names,
or content. No automatic resolution is performed.

## Validation rules

1. Every course classId must resolve to exactly one class in the same source database.
2. Every session classId must resolve to exactly one class in the same source database.
3. If a session has courseId, the course must exist and belong to the same classId.
4. Every non-null class folderId must resolve to a folder owned by the same exact owner UID.
5. A folder parentFolderId must resolve to a folder with the same owner UID; detect cycles.
6. Firebase UID to account mapping must be one-to-one. Do not infer identity from email,
   name, phone, role, title, or content similarity.
7. No cross-source deduplication is allowed during inventory. Report possible duplicates
   as review candidates only, never as merge instructions.
8. Missing, ambiguous, or conflicting ownership remains unresolved/quarantined.

## Synthetic test cases required before any real export

- one teacher with two classes, courses, sessions, and a folder;
- two teachers with same class title but different source IDs and owners;
- identical class metadata/content under different owner UIDs;
- blank ownerUid and unmatched ownerUid;
- duplicate numeric IDs in two different source databases;
- orphan course, orphan session, duplicate parent reference, and session/course class mismatch;
- class pointing to another teacher's folder;
- folder parent owned by another teacher and folder cycle;
- private backup records and shared-feed records with overlapping metadata;
- missing Firebase profile and duplicate UID-to-account mapping;
- disagreement between document-path owner and class owner, including blank-vs-present owner.

The validator passes only if it reports each fixture in the expected bucket and produces no
write operations. Tests must assert that the input files' checksums remain unchanged.

## Go/no-go criteria

No account linking or data movement begins until:

- all source exports are checksummed and their provenance is documented;
- synthetic tests pass;
- every ownership exception is either independently resolved by an approved operator or
  explicitly excluded/quarantined;
- a verified backup and a separately reviewed import/rollback plan exist;
- the user explicitly approves the next operational stage.

This specification does not authorize a production export, Firebase query, Room device
collection, or server migration.
