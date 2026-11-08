import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore, QueryDocumentSnapshot } from "firebase-admin/firestore";
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
    transaction.update(rootRef, { updatedAt: FieldValue.serverTimestamp(), adminPending: true });
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

  const root = await rootRef.get();
  const record = await recordRef.get();
  if (!root.exists || root.get("ownerUid") !== teacherUid || root.get("schemaVersion") !== 2) {
    throw new HttpsError("failed-precondition", "پشتیبان خصوصی استاد آماده مدیریت نیست یا ساختار آن پشتیبانی نمی‌شود.");
  }
  requireOwnedRecord(record, type, teacherUid);

  let toDelete: FirebaseFirestore.DocumentReference[] = [recordRef];
  if (type !== "class") {
    const classId = record.get("classId");
    if (typeof classId !== "number" || !Number.isSafeInteger(classId) || classId <= 0) {
      throw new HttpsError("failed-precondition", "ارتباط رکورد با کلاس معتبر نیست.");
    }
    const classRecord = await recordsRef.doc(recordKey("class", classId)).get();
    if (!classRecord.exists || classRecord.get("ownerUid") !== teacherUid) {
      throw new HttpsError("permission-denied", "مالکیت کلاس مقصد تأیید نشد.");
    }
    if (type === "course") {
      const dependentLectures = await recordsRef.where("courseId", "==", id).limit(1).get();
      if (!dependentLectures.empty && dependentLectures.docs[0].get("recordType") === "lecture") {
        throw new HttpsError("failed-precondition", "این درس جلسه دارد؛ ابتدا جلسات آن را جابه‌جا یا حذف کنید.");
      }
    }
  } else {
    const classId = id;
    const allRecords = await recordsRef.get();
    const children = allRecords.docs.filter((doc) =>
      (doc.get("recordType") === "course" || doc.get("recordType") === "lecture") &&
      doc.get("classId") === classId
    );
    toDelete = [recordRef, ...children.map((doc) => doc.ref)];
    if (toDelete.length > 450) {
      throw new HttpsError("resource-exhausted", "تعداد رکوردهای این کلاس برای حذف یک‌مرحله‌ای زیاد است؛ عملیات متوقف شد و هیچ رکوردی حذف نشد.");
    }
  }

  const batch = db.batch();
  toDelete.forEach((ref) => batch.delete(ref));
  batch.update(rootRef, {
    recordCount: Math.max(0, (root.get("recordCount") ?? toDelete.length) - toDelete.length),
    updatedAt: FieldValue.serverTimestamp(),
    adminPending: true,
  });
  batch.set(auditRef, {
    action: "delete",
    adminUid,
    targetTeacherUid: teacherUid,
    recordType: type,
    recordId: id,
    deletedRecordCount: toDelete.length,
    createdAt: FieldValue.serverTimestamp(),
  });
  await batch.commit();

  return { success: true, ownerUid: teacherUid, recordType: type, recordId: id, deletedRecordCount: toDelete.length };
});
