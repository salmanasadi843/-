import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment
} from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs, query, setDoc, updateDoc, where } from "firebase/firestore";

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

test("a user can create a normal STUDENT profile", async () => {
  const db = env.authenticatedContext("student-create").firestore();
  await assertSucceeds(
    setDoc(doc(db, "users/student-create"), {
      uid: "student-create",
      role: "STUDENT"
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

test("a user cannot approve themselves as a teacher", async () => {
  const db = env.authenticatedContext("student-approve").firestore();
  await assertFails(
    setDoc(doc(db, "approved_teachers/student-approve"), {
      approved: true
    })
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

test("a profile claiming TEACHER without trusted approval cannot publish", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "users/unapproved-teacher"), {
      uid: "unapproved-teacher",
      role: "TEACHER"
    });
  });

  const db = env.authenticatedContext("unapproved-teacher").firestore();
  await assertFails(
    setDoc(doc(db, "shared_materials/unapproved-teacher"), {
      ownerUid: "unapproved-teacher",
      payload: "{}",
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

test("an approved teacher can write their private backup", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await setDoc(doc(adminDb, "users/teacher-backup"), {
      uid: "teacher-backup",
      role: "TEACHER"
    });
    await setDoc(doc(adminDb, "approved_teachers/teacher-backup"), {
      approved: true
    });
  });

  const db = env.authenticatedContext("teacher-backup").firestore();
  await assertSucceeds(
    setDoc(doc(db, "teacher_data/teacher-backup"), {
      ownerUid: "teacher-backup",
      payload: "backup"
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


async function seedApprovedTeacher(uid, classId = "class-1", classPublished = true) {
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, `users/${uid}`), { uid, role: "TEACHER" });
    await setDoc(doc(db, `approved_teachers/${uid}`), { approved: true });
    await setDoc(doc(db, `published_classes/${uid}__${classId}`), {
      ownerUid: uid,
      classId,
      isPublished: classPublished,
      classJson: JSON.stringify({ id: classId, name: "کلاس آزمایشی" }),
      coursesJson: "[]"
    });
  });
}

test("students cannot read the legacy opaque aggregate payload", async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "shared_materials/teacher-1"), {
      ownerUid: "teacher-1",
      payload: JSON.stringify({ classes: [], courses: [], lectures: [] })
    });
  });
  const db = env.authenticatedContext("student-canonical-1").firestore();
  await assertFails(getDoc(doc(db, "shared_materials/teacher-1")));
});

test("students can read published class records but not hidden class records", async () => {
  await seedApprovedTeacher("teacher-publish", "visible", true);
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "published_classes/teacher-publish__hidden"), {
      ownerUid: "teacher-publish",
      classId: "hidden",
      isPublished: false,
      classJson: JSON.stringify({ id: "hidden", name: "پنهان" }),
      coursesJson: "[]"
    });
  });
  const db = env.authenticatedContext("student-class-read").firestore();
  await assertSucceeds(getDoc(doc(db, "published_classes/teacher-publish__visible")));
  await assertFails(getDoc(doc(db, "published_classes/teacher-publish__hidden")));
});

test("published class query is permitted only with the publication filter", async () => {
  await seedApprovedTeacher("teacher-query", "visible", true);
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "published_classes/teacher-query__hidden"), {
      ownerUid: "teacher-query", classId: "hidden", isPublished: false,
      classJson: "{}", coursesJson: "[]"
    });
  });
  const db = env.authenticatedContext("student-class-query").firestore();
  const result = await assertSucceeds(getDocs(query(
    collection(db, "published_classes"),
    where("isPublished", "==", true)
  )));
  assert.equal(result.size, 1);
});

test("approved teacher cannot publish a lesson under a hidden class", async () => {
  await seedApprovedTeacher("teacher-hidden-parent", "hidden-class", false);
  const db = env.authenticatedContext("teacher-hidden-parent").firestore();
  await assertFails(setDoc(doc(db, "published_lessons/teacher-hidden-parent__lesson-1"), {
    ownerUid: "teacher-hidden-parent",
    lessonId: "lesson-1",
    classId: "hidden-class",
    isPublished: true,
    lessonJson: JSON.stringify({ id: "lesson-1", transcript: "private" })
  }));
});

test("approved teacher can publish a lesson under a published class", async () => {
  await seedApprovedTeacher("teacher-visible-parent", "visible-class", true);
  const db = env.authenticatedContext("teacher-visible-parent").firestore();
  await assertSucceeds(setDoc(doc(db, "published_lessons/teacher-visible-parent__lesson-1"), {
    ownerUid: "teacher-visible-parent",
    lessonId: "lesson-1",
    classId: "visible-class",
    isPublished: true,
    lessonJson: JSON.stringify({ id: "lesson-1", transcript: "published transcript" })
  }));
  const studentDb = env.authenticatedContext("student-visible-lesson").firestore();
  await assertSucceeds(getDoc(doc(studentDb, "published_lessons/teacher-visible-parent__lesson-1")));
});

test("students cannot read a lesson after publication is revoked", async () => {
  await seedApprovedTeacher("teacher-revoke", "class-revoke", true);
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "published_lessons/teacher-revoke__lesson-revoked"), {
      ownerUid: "teacher-revoke",
      lessonId: "lesson-revoked",
      classId: "class-revoke",
      isPublished: false,
      lessonJson: JSON.stringify({ transcript: "must remain private" })
    });
  });
  const db = env.authenticatedContext("student-revoke").firestore();
  await assertFails(getDoc(doc(db, "published_lessons/teacher-revoke__lesson-revoked")));
});

test("students cannot forge canonical publication records", async () => {
  const db = env.authenticatedContext("student-forge-publication").firestore();
  await assertFails(setDoc(doc(db, "published_classes/student-forge-publication__class-1"), {
    ownerUid: "student-forge-publication",
    classId: "class-1",
    isPublished: true,
    classJson: "{}",
    coursesJson: "[]"
  }));
});
