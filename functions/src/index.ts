import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https";
import { sanitizePatch, validateRecordId, validateRecordType, RecordType } from "./validation.js";

initializeApp();
const db = getFirestore();

// This UID is the primary Darsyar administrator already designated by the
// repository's existing activeTeacher() policy. It is an identifier, not a secret.
const PRIMARY_ADMIN_UID = "xgUdnaQ6X0hPobOUwx3SDI0BJVc2";
const REGION = "europe-west1";
const RECORDS = "records";
const TEACHER_DATA = "teacher_data";

type AdminRequest = {
  targetTeacherUid?: unknown;
  recordType?: unknown;
  recordId?: unknown;
  patch?: unknown;
};

function requirePrimaryAdmin(request: CallableRequest<unknown>): string {
  const auth = request.auth;
  if (!auth) throw new HttpsError("unauthenticated", "برای این عملیات ابتدا وارد شوید.");
  if (
    auth.uid !== PRIMARY_ADMIN_UID ||
    auth.token.email !== "salmanasadi843@gmail.com" ||
    auth.token.email_verified !== true
  ) {
    throw new HttpsError("permission-denied", "این عملیات فقط برای مدیر اصلی مجاز است.");
  }
  return auth.uid;
}

function requireTargetUid(value: unknown): string {
  if (typeof value !== "string" || value.length < 10 || value.length > 128 || value === PRIMARY_ADMIN_UID) {
    throw new HttpsError("invalid-argument", "شناسه استاد مقصد معتبر نیست.");
  }
  return value;
}

function recordKey(type: RecordType, id: number): string {
  return `${type}_${id}`;
}

async function assertApprovedTeacher(uid: string): Promise<void> {
  const [profile, approval] = await Promise.all([
    db.collection("users").doc(uid).get(),
    db.collection("approved_teachers").doc(uid).get(),
  ]);
  if (!profile.exists || profile.get("role") !== "TEACHER") {
    throw new HttpsError("failed-precondition", "حساب مقصد، حساب استاد معتبر نیست.");
  }
  const enabled = approval.get("enabled") === true || approval.get("approved") === true;
  if (!enabled) {
    throw new HttpsError("failed-precondition", "مجوز فعال استاد مقصد تأیید نشد.");
  }
}

async function assertOwnedClass(
  transaction: FirebaseFirestore.Transaction,
  teacherUid: string,
  classId: number,
): Promise<void> {
  const ref = db.collection(TEACHER_DATA).doc(teacherUid).collection(RECORDS).doc(recordKey("class", classId));
  const snapshot = await transaction.get(ref);
  if (!snapshot.exists || snapshot.get("recordType") !== "class" || snapshot.get("ownerUid") !== teacherUid) {
    throw new HttpsError("permission-denied", "مالکیت کلاس مقصد تأیید نشد.");
  }
}

function requireOwnedRecord(
  snapshot: FirebaseFirestore.DocumentSnapshot,
  type: RecordType,
  teacherUid: string,
): void {
  if (!snapshot.exists || snapshot.get("recordType") !== type) {
    throw new HttpsError("not-found", "رکورد موردنظر پیدا نشد.");
  }
  if (type === "class" && snapshot.get("ownerUid") !== teacherUid) {
    throw new HttpsError("permission-denied", "مالکیت کلاس مقصد تأیید نشد.");
  }
}

export const adminUpdateTeacherRecord = onCall({ region: REGION, timeoutSeconds: 30 }, async (request) => {
  const adminUid = requirePrimaryAdmin(request);
  const data = (request.data ?? {}) as AdminRequest;
  const teacherUid = requireTargetUid(data.targetTeacherUid);
  const type = validateRecordType(data.recordType);
  const id = validateRecordId(data.recordId);
  const patch = sanitizePatch(type, data.patch);

  await assertApprovedTeacher(teacherUid);
  const rootRef = db.collection(TEACHER_DATA).doc(teacherUid);
  const recordRef = rootRef.collection(RECORDS).doc(recordKey(type, id));
  const auditRef = db.collection("admin_audit").doc();

  await db.runTransaction(async (transaction) => {
    const [root, record] = await Promise.all([
      transaction.get(rootRef),
      transaction.get(recordRef),
    ]);
    if (!root.exists || root.get("ownerUid") !== teacherUid || root.get("schemaVersion") !== 2) {
      throw new HttpsError("failed-precondition", "پشتیبان خصوصی استاد آماده مدیریت نیست یا ساختار آن پشتیبانی نمی‌شود.");
    }
    if ((root.get("syncLockUntilMillis") ?? 0) > Date.now()) {
      throw new HttpsError("aborted", "پشتیبان‌گیری استاد در حال انجام است؛ کمی بعد دوباره تلاش کنید.");
    }
    requireOwnedRecord(record, type, teacherUid);

    if (type !== "class") {
      const classId = record.get("classId");
      if (typeof classId !== "number" || !Number.isSafeInteger(classId) || classId <= 0) {
        throw new HttpsError("failed-precondition", "ارتباط رکورد با کلاس معتبر نیست.");
      }
      await assertOwnedClass(transaction, teacherUid, classId);
    }

    transaction.update(recordRef, {
      ...patch,
      ...(type === "lecture" ? { lastEditedMillis: Date.now() } : {}),
    });
    const pendingVersion = root.get("adminPendingVersion");
    transaction.update(rootRef, {
      updatedAt: FieldValue.serverTimestamp(),
      adminPending: true,
      adminPendingVersion: (typeof pendingVersion === "number" ? pendingVersion : 0) + 1,
    });
    transaction.set(auditRef, {
      action: "update",
      adminUid,
      targetTeacherUid: teacherUid,
      recordType: type,
      recordId: id,
      changedFields: Object.keys(patch),
      createdAt: FieldValue.serverTimestamp(),
    });
  });

  return { success: true, ownerUid: teacherUid, recordType: type, recordId: id };
});

export const adminDeleteTeacherRecord = onCall({ region: REGION, timeoutSeconds: 60 }, async (request) => {
  const adminUid = requirePrimaryAdmin(request);
  const data = (request.data ?? {}) as AdminRequest;
  const teacherUid = requireTargetUid(data.targetTeacherUid);
  const type = validateRecordType(data.recordType);
  const id = validateRecordId(data.recordId);

  await assertApprovedTeacher(teacherUid);
  const rootRef = db.collection(TEACHER_DATA).doc(teacherUid);
  const recordsRef = rootRef.collection(RECORDS);
  const recordRef = recordsRef.doc(recordKey(type, id));
  const auditRef = db.collection("admin_audit").doc();

  const deletedRecordCount = await db.runTransaction(async (transaction) => {
    const [root, record] = await Promise.all([
      transaction.get(rootRef),
      transaction.get(recordRef),
    ]);
    if (!root.exists || root.get("ownerUid") !== teacherUid || root.get("schemaVersion") !== 2) {
      throw new HttpsError("failed-precondition", "پشتیبان خصوصی استاد آماده مدیریت نیست یا ساختار آن پشتیبانی نمی‌شود.");
    }
    if ((root.get("syncLockUntilMillis") ?? 0) > Date.now()) {
      throw new HttpsError("aborted", "پشتیبان‌گیری استاد در حال انجام است؛ کمی بعد دوباره تلاش کنید.");
    }
    requireOwnedRecord(record, type, teacherUid);

    let toDelete: FirebaseFirestore.DocumentReference[] = [recordRef];
    if (type !== "class") {
      const classId = record.get("classId");
      if (typeof classId !== "number" || !Number.isSafeInteger(classId) || classId <= 0) {
        throw new HttpsError("failed-precondition", "ارتباط رکورد با کلاس معتبر نیست.");
      }
      const classRecord = await transaction.get(recordsRef.doc(recordKey("class", classId)));
      if (!classRecord.exists || classRecord.get("ownerUid") !== teacherUid) {
        throw new HttpsError("permission-denied", "مالکیت کلاس مقصد تأیید نشد.");
      }
      if (type === "course") {
        const dependentLectures = await transaction.get(recordsRef.where("courseId", "==", id));
        if (dependentLectures.docs.some((doc) => doc.get("recordType") === "lecture")) {
          throw new HttpsError("failed-precondition", "این درس جلسه دارد؛ ابتدا جلسات آن را جابه‌جا یا حذف کنید.");
        }
      }
    } else {
      const classChildren = await transaction.get(recordsRef.where("classId", "==", id));
      const children = classChildren.docs.filter((doc) =>
        doc.get("recordType") === "course" || doc.get("recordType") === "lecture"
      );
      toDelete = [recordRef, ...children.map((doc) => doc.ref)];
      if (toDelete.length > 450) {
        throw new HttpsError("resource-exhausted", "تعداد رکوردهای این کلاس برای حذف یک‌مرحله‌ای زیاد است؛ عملیات متوقف شد و هیچ رکوردی حذف نشد.");
      }
    }

    toDelete.forEach((ref) => transaction.delete(ref));
    const storedCount = root.get("recordCount");
    const pendingVersion = root.get("adminPendingVersion");
    const currentCount = typeof storedCount === "number" ? storedCount : toDelete.length;
    transaction.update(rootRef, {
      recordCount: Math.max(0, currentCount - toDelete.length),
      updatedAt: FieldValue.serverTimestamp(),
      adminPending: true,
      adminPendingVersion: (typeof pendingVersion === "number" ? pendingVersion : 0) + 1,
    });
    transaction.set(auditRef, {
      action: "delete",
      adminUid,
      targetTeacherUid: teacherUid,
      recordType: type,
      recordId: id,
      deletedRecordCount: toDelete.length,
      createdAt: FieldValue.serverTimestamp(),
    });
    return toDelete.length;
  });

  return { success: true, ownerUid: teacherUid, recordType: type, recordId: id, deletedRecordCount };
});


export const adminListTeachers = onCall({ region: REGION, timeoutSeconds: 30 }, async (request) => {
  requirePrimaryAdmin(request);
  const profiles = await db.collection("users").where("role", "==", "TEACHER").limit(200).get();
  const teachers = await Promise.all(profiles.docs.map(async (profile) => {
    const uid = profile.id;
    if (uid === PRIMARY_ADMIN_UID) return null;
    const approval = await db.collection("approved_teachers").doc(uid).get();
    if (approval.get("enabled") !== true && approval.get("approved") !== true) return null;
    return {
      uid,
      name: profile.get("teacherName") ?? profile.get("name") ?? "",
      email: profile.get("email") ?? "",
    };
  }));
  return { teachers: teachers.filter((teacher): teacher is NonNullable<typeof teacher> => teacher !== null) };
});

export const adminListTeacherRecords = onCall({ region: REGION, timeoutSeconds: 30 }, async (request) => {
  requirePrimaryAdmin(request);
  const data = (request.data ?? {}) as AdminRequest;
  const teacherUid = requireTargetUid(data.targetTeacherUid);
  await assertApprovedTeacher(teacherUid);
  const root = await db.collection(TEACHER_DATA).doc(teacherUid).get();
  if (!root.exists || root.get("ownerUid") !== teacherUid || root.get("schemaVersion") !== 2) {
    throw new HttpsError("failed-precondition", "پشتیبان خصوصی استاد آماده مدیریت نیست یا ساختار آن پشتیبانی نمی‌شود.");
  }
  if ((root.get("syncLockUntilMillis") ?? 0) > Date.now()) {
    throw new HttpsError("aborted", "پشتیبان‌گیری استاد در حال انجام است؛ کمی بعد دوباره تلاش کنید.");
  }
  const records = await db.collection(TEACHER_DATA).doc(teacherUid).collection(RECORDS).limit(1500).get();
  const items = records.docs.map((doc) => {
    const value = doc.data();
    const type = value.recordType;
    const id = Number(value.id);
    if ((type !== "class" && type !== "course" && type !== "lecture") ||
        !Number.isSafeInteger(id) || id <= 0) return null;
    return {
      id,
      recordType: type,
      classId: typeof value.classId === "number" ? value.classId : null,
      courseId: typeof value.courseId === "number" ? value.courseId : null,
      title: type === "lecture" ? (value.title ?? "") : (value.name ?? ""),
      isPublished: value.isPublished === true,
      dateMillis: typeof value.dateMillis === "number" ? value.dateMillis : null,
    };
  }).filter((item): item is NonNullable<typeof item> => item !== null);
  return { records: items };
});

export const adminGetTeacherRecord = onCall({ region: REGION, timeoutSeconds: 30 }, async (request) => {
  requirePrimaryAdmin(request);
  const data = (request.data ?? {}) as AdminRequest;
  const teacherUid = requireTargetUid(data.targetTeacherUid);
  const type = validateRecordType(data.recordType);
  const id = validateRecordId(data.recordId);
  await assertApprovedTeacher(teacherUid);
  const root = await db.collection(TEACHER_DATA).doc(teacherUid).get();
  if (!root.exists || root.get("ownerUid") !== teacherUid || root.get("schemaVersion") !== 2) {
    throw new HttpsError("failed-precondition", "پشتیبان خصوصی استاد آماده مدیریت نیست یا ساختار آن پشتیبانی نمی‌شود.");
  }
  if ((root.get("syncLockUntilMillis") ?? 0) > Date.now()) {
    throw new HttpsError("aborted", "پشتیبان‌گیری استاد در حال انجام است؛ کمی بعد دوباره تلاش کنید.");
  }
  const ref = db.collection(TEACHER_DATA).doc(teacherUid).collection(RECORDS).doc(recordKey(type, id));
  const snapshot = await ref.get();
  requireOwnedRecord(snapshot, type, teacherUid);
  if (type !== "class") {
    const classId = snapshot.get("classId");
    if (typeof classId !== "number" || !Number.isSafeInteger(classId) || classId <= 0) {
      throw new HttpsError("failed-precondition", "ارتباط رکورد با کلاس معتبر نیست.");
    }
    const classRecord = await db.collection(TEACHER_DATA).doc(teacherUid).collection(RECORDS).doc(recordKey("class", classId)).get();
    if (!classRecord.exists || classRecord.get("ownerUid") !== teacherUid) {
      throw new HttpsError("permission-denied", "مالکیت کلاس مقصد تأیید نشد.");
    }
  }
  return { record: snapshot.data() };
});
