#!/usr/bin/env python3
"""Read-only, privacy-preserving Darsyar legacy inventory validator.

Input: one JSON file per source database/export. No network access and no writes to inputs.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import hmac
import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

VERSION = "0.3.0"
CLASS_STATES = ("matched_uid", "blank_owner", "unknown_uid", "conflicting_owner", "invalid_record")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pseudonym(value: str | None, key: bytes) -> str | None:
    if value is None or not str(value).strip():
        return None
    return hmac.new(key, str(value).encode("utf-8"), hashlib.sha256).hexdigest()[:20]


def load_source(path: Path) -> tuple[dict[str, Any], str]:
    before = sha256(path)
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"{path.name}: top-level JSON must be an object")
    return data, before


def run_inventory(paths: list[Path], key: bytes) -> dict[str, Any]:
    report: dict[str, Any] = {
        "validator_version": VERSION,
        "sources": [],
        "aggregates": {
            "accounts": {"profile_present": 0, "profile_missing": 0, "role_field": {}},
            "classes_by_ownership": {state: 0 for state in CLASS_STATES},
            "classes_by_visibility": {},
            "courses_by_ownership": {},
            "sessions_by_ownership": {},
            "folders": {"exact_owner_reference": 0, "blank_or_unknown_owner": 0,
                        "invalid_parent": 0, "cycles": 0},
            "orphans": {"courses": 0, "sessions": 0},
            "ambiguous_parent_references": {"courses": 0, "sessions": 0},
            "records_by_population": {"shared_feed": 0, "private_backup": 0, "device_local": 0},
            "duplicate_ids_within_source": 0,
            "rows_processed": 0,
        },
        "exceptions": [],
    }
    uid_to_accounts: dict[str, set[str]] = defaultdict(set)
    loaded: list[tuple[Path, dict[str, Any], str]] = []
    for path in paths:
        data, digest = load_source(path)
        loaded.append((path, data, digest))
        for account in data.get("accounts", []):
            uid = str(account.get("uid", "")).strip()
            account_id = str(account.get("account_id", "")).strip()
            if uid and account_id:
                uid_to_accounts[uid].add(account_id)

    known_uids = set(uid_to_accounts)
    ambiguous_uids = {uid for uid, ids in uid_to_accounts.items() if len(ids) != 1}

    for path, data, digest in loaded:
        source_id = str(data.get("source_id") or path.stem)
        source_ref = pseudonym(source_id, key)
        source_type = str(data.get("source_type") or "unknown")
        counts: Counter[str] = Counter()

        accounts = data.get("accounts", [])
        for account in accounts:
            counts["accounts"] += 1
            agg = report["aggregates"]["accounts"]
            agg["profile_present" if account.get("profile_present") else "profile_missing"] += 1
            role = str(account.get("role_field") or "absent")
            agg["role_field"][role] = agg["role_field"].get(role, 0) + 1

        def add_exception(kind: str, row_id: Any, owner: str | None = None) -> None:
            report["exceptions"].append({
                "kind": kind, "source_ref": source_ref,
                "row_id": str(row_id) if row_id is not None else None,
                "owner_ref": pseudonym(owner, key),
            })

        def index_rows(rows: list[dict[str, Any]], table: str) -> tuple[dict[str, dict[str, Any]], set[str]]:
            grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
            for item in rows:
                if item.get("id") is not None:
                    grouped[str(item["id"])].append(item)
            duplicates = {row_id for row_id, items in grouped.items() if len(items) > 1}
            for row_id in duplicates:
                add_exception("duplicate_" + table + "_id_within_source", row_id)
            return ({row_id: items[0] for row_id, items in grouped.items() if len(items) == 1}, duplicates)

        # Duplicate IDs are deliberately excluded from reference lookup maps.
        folders, duplicate_folder_ids = index_rows(data.get("folders", []), "folders")
        classes, duplicate_class_ids = index_rows(data.get("classes", []), "classes")
        courses, duplicate_course_ids = index_rows(data.get("courses", []), "courses")
        sessions, duplicate_session_ids = index_rows(data.get("sessions", []), "sessions")

        def class_state(row: dict[str, Any]) -> str:
            raw_owner = row.get("owner_uid")
            owner = str(raw_owner).strip() if raw_owner is not None else ""
            raw_document_owner = row.get("document_owner_uid")
            document_owner = str(raw_document_owner).strip() if raw_document_owner is not None else ""
            if row.get("invalid") or row.get("id") is None:
                return "invalid_record"
            if (row.get("owner_conflict") or (owner and owner in ambiguous_uids)
                    or (document_owner and document_owner != owner)):
                return "conflicting_owner"
            if not owner:
                return "blank_owner"
            if owner not in known_uids:
                return "unknown_uid"
            return "matched_uid"

        class_states: dict[str, str] = {}
        for row in data.get("classes", []):
            counts["classes"] += 1
            rid = row.get("id")
            state = class_state(row)
            if rid is not None and str(rid) not in duplicate_class_ids:
                class_states[str(rid)] = state
            report["aggregates"]["classes_by_ownership"][state] += 1
            visibility = str(row.get("visibility") or ("published" if row.get("published") else "unpublished"))
            report["aggregates"]["classes_by_visibility"][visibility] = report["aggregates"]["classes_by_visibility"].get(visibility, 0) + 1
            if state != "matched_uid":
                add_exception("class_" + state, rid, row.get("owner_uid"))
            folder_id = row.get("folder_id")
            if folder_id is not None:
                folder = folders.get(str(folder_id))
                if not folder or str(folder.get("owner_uid") or "") != str(row.get("owner_uid") or ""):
                    add_exception("class_folder_owner_or_reference_mismatch", rid, row.get("owner_uid"))

        def inherited(row: dict[str, Any], kind: str) -> str:
            parent_id = row.get("class_id")
            if parent_id is None:
                report["aggregates"]["orphans"][kind] += 1
                add_exception("orphan_" + kind, row.get("id"))
                return "orphan"
            parent_key = str(parent_id)
            if parent_key in duplicate_class_ids:
                report["aggregates"]["ambiguous_parent_references"][kind] += 1
                add_exception("ambiguous_parent_class_for_" + kind, row.get("id"))
                return "ambiguous_parent"
            if parent_key not in classes:
                report["aggregates"]["orphans"][kind] += 1
                add_exception("orphan_" + kind, row.get("id"))
                return "orphan"
            return class_states.get(parent_key, "invalid_record")

        for row in data.get("courses", []):
            counts["courses"] += 1
            state = inherited(row, "courses")
            bucket = report["aggregates"]["courses_by_ownership"]
            bucket[state] = bucket.get(state, 0) + 1

        for row in data.get("sessions", []):
            counts["sessions"] += 1
            state = inherited(row, "sessions")
            if row.get("course_id") is not None:
                course = courses.get(str(row["course_id"]))
                if not course or str(course.get("class_id")) != str(row.get("class_id"))):
                    add_exception("session_course_class_mismatch", row.get("id"))
            bucket = report["aggregates"]["sessions_by_ownership"]
            bucket[state] = bucket.get(state, 0) + 1

        # Folder owner validity and parent chains are scoped to this source only.
        for row in data.get("folders", []):
            counts["folders"] += 1
            owner = str(row.get("owner_uid") or "").strip()
            valid_owner = bool(owner and owner in known_uids and owner not in ambiguous_uids)
            parent_id = row.get("parent_folder_id")
            parent = folders.get(str(parent_id)) if parent_id is not None else None
            if not valid_owner:
                report["aggregates"]["folders"]["blank_or_unknown_owner"] += 1
                add_exception("folder_owner_unresolved", row.get("id"), owner)
            elif parent_id is None or (parent and str(parent.get("owner_uid") or "") == owner):
                report["aggregates"]["folders"]["exact_owner_reference"] += 1
            else:
                report["aggregates"]["folders"]["invalid_parent"] += 1
                add_exception("folder_parent_missing_or_owner_mismatch", row.get("id"), owner)

        cycle_nodes: set[str] = set()
        globally_done: set[str] = set()
        for start in folders:
            path_nodes: list[str] = []
            path_index: dict[str, int] = {}
            current: str | None = start
            while current is not None and current in folders and current not in globally_done:
                if current in path_index:
                    cycle_nodes.update(path_nodes[path_index[current]:])
                    break
                path_index[current] = len(path_nodes)
                path_nodes.append(current)
                parent = folders[current].get("parent_folder_id")
                current = str(parent) if parent is not None else None
            globally_done.update(path_nodes)
        if cycle_nodes:
            report["aggregates"]["folders"]["cycles"] += len(cycle_nodes)
            for folder_id in sorted(cycle_nodes):
                add_exception("folder_cycle", folder_id, folders[folder_id].get("owner_uid"))

        report["aggregates"]["duplicate_ids_within_source"] += (
            len(duplicate_folder_ids) + len(duplicate_class_ids)
            + len(duplicate_course_ids) + len(duplicate_session_ids)
        )

        pop = report["aggregates"]["records_by_population"]
        for name in pop:
            pop[name] += int(data.get("record_counts", {}).get(name, 0) or 0)
        counts["records"] = sum(int(v or 0) for v in data.get("record_counts", {}).values())
        report["aggregates"]["rows_processed"] += sum(counts.values())
        report["sources"].append({
            "source_ref": source_ref, "source_type": source_type,
            "extracted_at": data.get("extracted_at"),
            "sha256": digest, "row_counts": dict(counts),
        })

    # Identity ambiguity is reported without exposing UIDs or account IDs.
    report["aggregates"]["accounts"]["ambiguous_uid_mappings"] = len(ambiguous_uids)
    return report


def write_outputs(report: dict[str, Any], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "inventory.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    rows = []
    for group, values in report["aggregates"].items():
        if isinstance(values, dict):
            for key, value in values.items():
                if isinstance(value, (str, int, float, bool)) or value is None:
                    rows.append((group, key, value))
                elif isinstance(value, dict):
                    for subkey, subvalue in value.items():
                        rows.append((group + "." + key, subkey, subvalue))
    with (out_dir / "inventory.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(("category", "item", "count_or_value"))
        writer.writerows(rows)
    with (out_dir / "exceptions.restricted.json").open("w", encoding="utf-8") as stream:
        json.dump(report["exceptions"], stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", action="append", required=True, help="Input JSON file; repeat per source")
    parser.add_argument("--out", required=True, help="Output directory (must not be an input directory)")
    parser.add_argument("--hmac-key-env", default="DARSYAR_INVENTORY_HMAC_KEY",
                        help="Environment variable containing a random secret key")
    args = parser.parse_args()
    secret = os.environ.get(args.hmac_key_env)
    if not secret or len(secret.encode("utf-8")) < 32:
        parser.error(f"{args.hmac_key_env} must contain at least 32 bytes of random secret material")
    paths = [Path(p).resolve() for p in args.input]
    out_dir = Path(args.out).resolve()
    if any(out_dir == p.parent or out_dir in p.parents for p in paths):
        parser.error("output directory must not contain or overwrite an input file")
    try:
        before = {p: sha256(p) for p in paths}
        report = run_inventory(paths, secret.encode("utf-8"))
        after = {p: sha256(p) for p in paths}
        if before != after:
            raise RuntimeError("input checksum changed during validation; refusing to write report")
        write_outputs(report, out_dir)
    except Exception as exc:
        print(f"inventory failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2
    print(f"Validated {len(paths)} source(s); processed {report['aggregates']['rows_processed']} rows.")
    print("Input checksums unchanged. No source data was modified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
