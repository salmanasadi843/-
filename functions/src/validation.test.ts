import test from "node:test";
import assert from "node:assert/strict";
import { HttpsError } from "firebase-functions/v2/https";
import { sanitizePatch, validateRecordId, validateRecordType } from "./validation.js";

test("accepts only supported record types", () => {
  assert.equal(validateRecordType("class"), "class");
  assert.throws(() => validateRecordType("user"), HttpsError);
});

test("record IDs must be positive safe integers", () => {
  assert.equal(validateRecordId(42), 42);
  for (const value of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, "42"]) {
    assert.throws(() => validateRecordId(value), HttpsError);
  }
});

test("class patch accepts editable fields", () => {
  assert.deepEqual(sanitizePatch("class", { name: "فقه", isPublished: false }), {
    name: "فقه",
    isPublished: false,
  });
});

test("patch rejects ownership and relationship changes", () => {
  for (const patch of [
    { ownerUid: "attacker" },
    { id: 99 },
    { classId: 99 },
    { courseId: 99 },
    { folderId: 99 },
    {},
  ]) {
    assert.throws(() => sanitizePatch("lecture", patch), HttpsError);
  }
});

test("patch rejects incorrect value types and oversized text", () => {
  assert.throws(() => sanitizePatch("lecture", { isPublished: "false" }), HttpsError);
  assert.throws(() => sanitizePatch("class", { name: "x".repeat(200_001) }), HttpsError);
  assert.throws(() => sanitizePatch("course", { name: 10 }), HttpsError);
});

test("nullable lecture fields can be cleared", () => {
  assert.deepEqual(sanitizePatch("lecture", { audioUrl: null, aiSummary: null }), {
    audioUrl: null,
    aiSummary: null,
  });
});
