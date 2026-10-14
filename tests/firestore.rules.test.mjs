import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment
} from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc } from "firebase/firestore";

const projectId = "darsyar-rules-test";
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    firestore: { rules: readFileSync("firestore.rules", "utf8") }
  });
});

beforeEach(async () => {
  await env.clearFirestore();
});

after(async () => {
  await env?.cleanup();
});

test("a user cannot create their own profile with the TEACHER role", async () => {
  const db = env.authenticatedContext("new-user").firestore();
  await assertFails(
    setDoc(doc(db, "users/new-user"), {
      uid: "new-user",
      role: "TEACHER"
    })
  );
});

test("student cannot promote their existing profile to TEACHER", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "users/student-1"), {
      uid: "student-1",
      role: "STUDENT"
    });
  });

  const db = env.authenticatedContext("student-1").firestore();
  await assertFails(
    updateDoc(doc(db, "users/student-1"), { role: "TEACHER" })
  );
});

test("student cannot publish a forged shared-material payload", async () => {
  const db = env.authenticatedContext("student-2").firestore();
  await assertFails(
    setDoc(doc(db, "shared_materials/student-2"), {
      ownerUid: "student-2",
      payload: JSON.stringify({
        classes: [{ id: 1, name: "جعلی", isPublished: true }],
        courses: [],
        lectures: []
      }),
      updatedAt: Date.now()
    })
  );
});

test("an approved teacher can publish their own materials", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await setDoc(doc(adminDb, "users/teacher-1"), {
      uid: "teacher-1",
      role: "TEACHER"
    });
    await setDoc(doc(adminDb, "approved_teachers/teacher-1"), {
      approved: true
    });
  });

  const db = env.authenticatedContext("teacher-1").firestore();
  await assertSucceeds(
    setDoc(doc(db, "shared_materials/teacher-1"), {
      ownerUid: "teacher-1",
      payload: JSON.stringify({ classes: [], courses: [], lectures: [] }),
      updatedAt: Date.now()
    })
  );
});

test("discussion paths remain denied until trusted authorization exists", async () => {
  const db = env.authenticatedContext("student-3").firestore();
  await assertFails(
    setDoc(doc(db, "discussion_threads/thread-1"), {
      teacherUid: "teacher-1",
      classId: "class-1",
      authorUid: "student-3",
      authorRole: "STUDENT",
      title: "پرسش آزمایشی",
      questionText: "این نوشته نباید فعلاً ذخیره شود."
    })
  );
});

test("a user cannot read another user's private teacher backup", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "teacher_data/teacher-1"), {
      ownerUid: "teacher-1",
      payload: "private"
    });
  });

  const studentDb = env.authenticatedContext("student-4").firestore();
  await assertFails(getDoc(doc(studentDb, "teacher_data/teacher-1")));
});

test("legacy shared links are currently public; this behavior requires a product decision", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "shared/legacy-link"), {
      ownerUid: "teacher-1",
      title: "legacy share"
    });
  });

  const anonymousDb = env.unauthenticatedContext().firestore();
  await assertSucceeds(getDoc(doc(anonymousDb, "shared/legacy-link")));
});

test("student cannot create a fake publication record under another teacher", async () => {
  const db = env.authenticatedContext("student-5").firestore();
  await assertFails(
    setDoc(doc(db, "teacher_data/teacher-1/records/class-publication"), {
      ownerUid: "teacher-1",
      classId: "class-1",
      isPublished: true
    })
  );
});
