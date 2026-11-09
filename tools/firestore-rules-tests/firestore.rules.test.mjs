import { readFileSync } from "node:fs";
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";

const projectId = "demo-darsyar-rules";
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") }
  });
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.doc("users/teacher-1").set({ role: "TEACHER" });
    await db.doc("approved_teachers/teacher-1").set({ enabled: true, approved: true });
    await db.doc("teacher_data/teacher-1").set({
      ownerUid: "teacher-1", schemaVersion: 3, activeGeneration: "gen-1", recordCount: 1, folders: []
    });
    await db.doc("teacher_data/teacher-1/generations/gen-1/records/class_1").set({
      ownerUid: "teacher-1", recordType: "class", id: 1
    });
  });
});

after(async () => { await env?.cleanup(); });

test("unauthenticated users cannot read private teacher backups", async () => {
  await assertFails(env.unauthenticatedContext().firestore().doc("teacher_data/teacher-1").get());
});

test("another teacher cannot read a teacher backup", async () => {
  const db = env.authenticatedContext("teacher-2", { email: "other@example.com" }).firestore();
  await assertFails(db.doc("teacher_data/teacher-1").get());
  await assertFails(db.doc("teacher_data/teacher-1/generations/gen-1/records/class_1").get());
});

test("the primary admin can read any teacher root and generation records", async () => {
  const db = env.authenticatedContext("admin-uid", { email: "salmanasadi843@gmail.com" }).firestore();
  await assertSucceeds(db.doc("teacher_data/teacher-1").get());
  await assertSucceeds(db.doc("teacher_data/teacher-1/generations/gen-1/records/class_1").get());
});

test("the primary admin can create owner-bound restore records but cannot mutate or delete them", async () => {
  const db = env.authenticatedContext("admin-uid", { email: "salmanasadi843@gmail.com" }).firestore();
  const record = db.doc("teacher_data/teacher-1/generations/admin_restore/records/class_2");
  await assertSucceeds(record.set({ ownerUid: "teacher-1", recordType: "class", id: 2 }));
  await assertFails(record.set({ ownerUid: "teacher-2", recordType: "class", id: 2 }));
  await assertFails(record.update({ id: 3 }));
  await assertFails(record.delete());
});

test("the primary admin can update only approved backup-root fields and cannot delete roots", async () => {
  const db = env.authenticatedContext("admin-uid", { email: "salmanasadi843@gmail.com" }).firestore();
  const root = db.doc("teacher_data/teacher-1");
  await assertSucceeds(root.update({ activeGeneration: "gen-2", recordCount: 1 }));
  await assertFails(root.update({ arbitraryAdminNote: "not allowed" }));
  await assertFails(root.delete());
});

test("an approved teacher can manage only their own backup records", async () => {
  const db = env.authenticatedContext("teacher-1", { email: "teacher@example.com" }).firestore();
  const record = db.doc("teacher_data/teacher-1/generations/gen-1/records/own");
  await assertSucceeds(record.set({ ownerUid: "teacher-1", recordType: "class", id: 2 }));
  await assertSucceeds(record.update({ id: 3 }));
  await assertSucceeds(record.delete());
  await assertFails(db.doc("teacher_data/teacher-2/generations/gen-1/records/foreign").set({
    ownerUid: "teacher-2", recordType: "class", id: 4
  }));
});

test("the primary admin cannot create a record under another teacher with a forged owner UID", async () => {
  const db = env.authenticatedContext("admin-uid", { email: "salmanasadi843@gmail.com" }).firestore();
  const forged = db.doc("teacher_data/teacher-1/generations/admin_restore/records/forged");
  await assertFails(forged.set({ ownerUid: "teacher-2", recordType: "class", id: 3 }));
});

test("the primary admin can create a missing backup root only when its owner UID matches the path", async () => {
  const db = env.authenticatedContext("admin-uid", { email: "salmanasadi843@gmail.com" }).firestore();
  await assertSucceeds(db.doc("teacher_data/teacher-3").set({
    ownerUid: "teacher-3", schemaVersion: 3, activeGeneration: "gen-1", recordCount: 0, folders: []
  }));
  await assertFails(db.doc("teacher_data/teacher-4").set({
    ownerUid: "teacher-3", schemaVersion: 3, activeGeneration: "gen-1", recordCount: 0, folders: []
  }));
});
