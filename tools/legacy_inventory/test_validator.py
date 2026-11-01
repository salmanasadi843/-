import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from validator import run_inventory


class InventoryValidatorTest(unittest.TestCase):
    def test_ownership_relationships_privacy_and_read_only(self):
        fixture = {
            "source_id": "synthetic-device-A",
            "source_type": "room",
            "extracted_at": "2026-11-01T00:00:00Z",
            "accounts": [
                {"uid": "uid-teacher-a", "account_id": "account-a",
                 "profile_present": True, "role_field": "teacher"},
                {"uid": "uid-teacher-b", "account_id": "account-b",
                 "profile_present": False, "role_field": "absent"},
            ],
            "folders": [
                {"id": 1, "owner_uid": "uid-teacher-a", "parent_folder_id": None},
                {"id": 2, "owner_uid": "uid-teacher-b", "parent_folder_id": 1},
                {"id": 3, "owner_uid": "uid-teacher-a", "parent_folder_id": 4},
                {"id": 4, "owner_uid": "uid-teacher-a", "parent_folder_id": 3},
            ],
            "classes": [
                {"id": 10, "owner_uid": "uid-teacher-a", "folder_id": 1, "published": True},
                {"id": 11, "owner_uid": "", "folder_id": None},
                {"id": 12, "owner_uid": "uid-not-exported", "folder_id": None},
                {"id": 13, "owner_uid": "uid-teacher-a", "folder_id": 2},
            ],
            "courses": [{"id": 20, "class_id": 10}, {"id": 21, "class_id": 999}],
            "sessions": [
                {"id": 30, "class_id": 10, "course_id": 20},
                {"id": 31, "class_id": 10, "course_id": 21},
                {"id": 32, "class_id": 999, "course_id": None},
            ],
            "record_counts": {"shared_feed": 2, "private_backup": 1, "device_local": 4},
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.json"
            path.write_text(json.dumps(fixture), encoding="utf-8")
            before = hashlib.sha256(path.read_bytes()).hexdigest()
            report = run_inventory([path], b"x" * 32)
            after = hashlib.sha256(path.read_bytes()).hexdigest()

        self.assertEqual(before, after)
        agg = report["aggregates"]
        self.assertEqual(agg["classes_by_ownership"]["matched_uid"], 2)
        self.assertEqual(agg["classes_by_ownership"]["blank_owner"], 1)
        self.assertEqual(agg["classes_by_ownership"]["unknown_uid"], 1)
        self.assertEqual(agg["orphans"]["courses"], 1)
        self.assertEqual(agg["orphans"]["sessions"], 1)
        self.assertGreaterEqual(agg["folders"]["cycles"], 2)
        self.assertEqual(agg["records_by_population"]["shared_feed"], 2)
        rendered = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("uid-teacher-a", rendered)
        self.assertNotIn("uid-teacher-b", rendered)
        self.assertNotIn("uid-not-exported", rendered)
        kinds = {item["kind"] for item in report["exceptions"]}
        self.assertIn("session_course_class_mismatch", kinds)
        self.assertIn("class_folder_owner_or_reference_mismatch", kinds)

    def test_duplicate_uid_to_account_mapping_is_ambiguous(self):
        fixture = {
            "source_id": "synthetic-identity-source",
            "accounts": [
                {"uid": "same-uid", "account_id": "account-1"},
                {"uid": "same-uid", "account_id": "account-2"},
            ],
            "classes": [{"id": 1, "owner_uid": "same-uid"}],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "identity.json"
            path.write_text(json.dumps(fixture), encoding="utf-8")
            report = run_inventory([path], b"y" * 32)
        self.assertEqual(report["aggregates"]["accounts"]["ambiguous_uid_mappings"], 1)
        self.assertEqual(report["aggregates"]["classes_by_ownership"]["conflicting_owner"], 1)

    def test_duplicate_class_id_is_ambiguous_parent_not_an_orphan(self):
        fixture = {
            "source_id": "synthetic-duplicate-parent",
            "accounts": [{"uid": "teacher-a", "account_id": "a"}],
            "classes": [
                {"id": 8, "owner_uid": "teacher-a"},
                {"id": 8, "owner_uid": "teacher-a"},
            ],
            "courses": [{"id": 9, "class_id": 8}],
            "sessions": [{"id": 10, "class_id": 8}],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate-parent.json"
            path.write_text(json.dumps(fixture), encoding="utf-8")
            report = run_inventory([path], b"z" * 32)
        agg = report["aggregates"]
        self.assertEqual(agg["orphans"]["courses"], 0)
        self.assertEqual(agg["orphans"]["sessions"], 0)
        self.assertEqual(agg["ambiguous_parent_references"]["courses"], 1)
        self.assertEqual(agg["ambiguous_parent_references"]["sessions"], 1)
        self.assertEqual(agg["courses_by_ownership"]["ambiguous_parent"], 1)
        self.assertEqual(agg["sessions_by_ownership"]["ambiguous_parent"], 1)

    def test_blank_owner_with_independent_document_owner_is_conflicting(self):
        fixture = {
            "source_id": "synthetic-owner-disagreement",
            "accounts": [{"uid": "teacher-a", "account_id": "a"}],
            "classes": [
                {"id": 1, "owner_uid": "", "document_owner_uid": "teacher-a"},
                {"id": 2, "owner_uid": "teacher-a", "document_owner_uid": ""},
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "owner-disagreement.json"
            path.write_text(json.dumps(fixture), encoding="utf-8")
            report = run_inventory([path], b"w" * 32)
        states = report["aggregates"]["classes_by_ownership"]
        self.assertEqual(states["conflicting_owner"], 1)
        self.assertEqual(states["matched_uid"], 1)
        self.assertEqual(states["blank_owner"], 0)


    def test_numeric_ids_are_source_scoped_and_duplicate_folder_parent_is_invalid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_a = root / "device-a.json"
            source_b = root / "device-b.json"
            source_a.write_text(json.dumps({
                "source_id": "device-a",
                "accounts": [{"uid": "teacher-a", "account_id": "a"}],
                "folders": [
                    {"id": 1, "owner_uid": "teacher-a", "parent_folder_id": None},
                    {"id": 1, "owner_uid": "teacher-a", "parent_folder_id": None},
                    {"id": 2, "owner_uid": "teacher-a", "parent_folder_id": 1},
                ],
                "classes": [{"id": 7, "owner_uid": "teacher-a", "folder_id": 1}],
                "courses": [{"id": 4, "class_id": 7}],
                "sessions": [],
            }), encoding="utf-8")
            source_b.write_text(json.dumps({
                "source_id": "device-b",
                "accounts": [{"uid": "teacher-b", "account_id": "b"}],
                "classes": [{"id": 7, "owner_uid": "teacher-b"}],
                "courses": [{"id": 4, "class_id": 7}],
                "sessions": [],
            }), encoding="utf-8")
            report = run_inventory([source_a, source_b], b"v" * 32)

        agg = report["aggregates"]
        # IDs 7 and 4 are repeated across sources, but are not duplicates within either source.
        self.assertEqual(agg["duplicate_ids_within_source"], 1)
        self.assertEqual(agg["courses_by_ownership"]["matched_uid"], 2)
        self.assertEqual(agg["folders"]["invalid_parent"], 2)
        kinds = [item["kind"] for item in report["exceptions"]]
        self.assertIn("duplicate_folders_id_within_source", kinds)
        self.assertIn("folder_parent_missing_or_owner_mismatch", kinds)
        self.assertIn("class_folder_owner_or_reference_mismatch", kinds)


if __name__ == "__main__":
    unittest.main()
