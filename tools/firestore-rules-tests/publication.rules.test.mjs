import fs from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment
} from "@firebase/rules-unit-testing";
import {
  collection,
  doc,
  getDoc,
  getDocs,
  setDoc
} from "firebase/firestore";

const projectId = "demo-darsyar-publication-rules";
const teacherUid = "teacher-owner";
const otherTeacherUid = "teacher-other";
const studentUid = "student-reader";

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId,
    firestore: {
      host: "127.0.0.1",
      port: 8080,
      rules: fs.readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8")
    }
  });
});

after(async () => {
  await testEnv?.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, "users", teacherUid), { role: "TEACHER" });
    await setDoc(doc(db, "approved_teachers", teacherUid), {
      ownerUid: teacherUid,
      enabled: true
    });
    await setDoc(doc(db, "users", otherTeacherUid), { role: "TEACHER" });
    await setDoc(doc(db, "approved_teachers", otherTeacherUid), {
      ownerUid: otherTeacherUid,
      enabled: true
    });
    await setDoc(doc(db, "users", studentUid), { role: "STUDENT" });
  });
});

test("approved teacher can publish a root and separate version-2 records", async () => {
  const db = testEnv.authenticatedContext(teacherUid).firestore();
  await assertSucceeds(setDoc(doc(db, "shared_materials", teacherUid), {
    ownerUid: teacherUid,
    schemaVersion: 2,
    activeGeneration: "generation-a",
    recordCount: 1
  }));
  await assertSucceeds(setDoc(
    doc(db, "shared_materials", teacherUid, "records", "generation-a__lecture_1"),
    {
      ownerUid: teacherUid,
      generation: "generation-a",
      recordType: "lecture",
      dataJson: JSON.stringify({ id: 1, transcript: "متن درس" })
    }
  ));
});

test("authenticated student can read published feed and its records", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, "shared_materials", teacherUid), {
      ownerUid: teacherUid,
      schemaVersion: 2,
      activeGeneration: "generation-a",
      recordCount: 1
    });
    await setDoc(doc(db, "shared_materials", teacherUid, "records", "generation-a__class_1"), {
      ownerUid: teacherUid,
      generation: "generation-a",
      recordType: "class",
      dataJson: JSON.stringify({ id: 1, name: "کلاس آزمایشی" })
    });
  });

  const db = testEnv.authenticatedContext(studentUid).firestore();
  await assertSucceeds(getDoc(doc(db, "shared_materials", teacherUid)));
  const records = await assertSucceeds(
    getDocs(collection(db, "shared_materials", teacherUid, "records"))
  );
  if (records.size !== 1) throw new Error(`Expected 1 record, got ${records.size}`);
});

test("another teacher cannot write into the owner's publication feed", async () => {
  const db = testEnv.authenticatedContext(otherTeacherUid).firestore();
  await assertFails(setDoc(doc(db, "shared_materials", teacherUid), {
    ownerUid: teacherUid,
    schemaVersion: 2
  }));
  await assertFails(setDoc(
    doc(db, "shared_materials", teacherUid, "records", "forged-record"),
    {
      ownerUid: teacherUid,
      generation: "forged",
      recordType: "lecture",
      dataJson: "{}"
    }
  ));
});

test("student and unauthenticated users cannot publish records", async () => {
  const studentDb = testEnv.authenticatedContext(studentUid).firestore();
  await assertFails(setDoc(doc(studentDb, "shared_materials", studentUid), {
    ownerUid: studentUid,
    schemaVersion: 2
  }));

  const unauthenticatedDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(unauthenticatedDb, "shared_materials", teacherUid)));
});

test("record creation rejects wrong owner, generation type, record type, or payload type", async () => {
  const db = testEnv.authenticatedContext(teacherUid).firestore();
  const base = {
    ownerUid: teacherUid,
    generation: "generation-a",
    recordType: "lecture",
    dataJson: "{}"
  };

  await assertFails(setDoc(
    doc(db, "shared_materials", teacherUid, "records", "wrong-owner"),
    { ...base, ownerUid: otherTeacherUid }
  ));
  await assertFails(setDoc(
    doc(db, "shared_materials", teacherUid, "records", "wrong-generation"),
    { ...base, generation: 7 }
  ));
  await assertFails(setDoc(
    doc(db, "shared_materials", teacherUid, "records", "wrong-type"),
    { ...base, recordType: "admin" }
  ));
  await assertFails(setDoc(
    doc(db, "shared_materials", teacherUid, "records", "wrong-payload"),
    { ...base, dataJson: { id: 1 } }
  ));
});
