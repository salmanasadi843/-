import { HttpsError } from "firebase-functions/v2/https";

export type RecordType = "class" | "course" | "lecture";

const FIELD_TYPES: Record<RecordType, Record<string, "string" | "number" | "boolean" | "nullableString">> = {
  class: {
    name: "string",
    teacherName: "string",
    description: "string",
    term: "string",
    classDateMillis: "number",
    isPublished: "boolean",
  },
  course: {
    name: "string",
    teacherName: "string",
    description: "string",
  },
  lecture: {
    title: "string",
    courseName: "string",
    professorName: "string",
    dateMillis: "number",
    audioUrl: "nullableString",
    audioDurationMs: "number",
    transcript: "string",
    originalTranscript: "string",
    aiSummary: "nullableString",
    aiKeyPoints: "nullableString",
    aiQuizJson: "nullableString",
    tags: "string",
    isFavorite: "boolean",
    isPublished: "boolean",
    lastEditedMillis: "number",
    sortOrder: "number",
  },
};

export function assertAdminMutationAllowed(adminPending: unknown, lockUntilMillis: unknown, now = Date.now()): void {
  if (adminPending === true) {
    throw new HttpsError("aborted", "تغییر مدیریتی قبلی هنوز در حساب استاد بازیابی نشده است.");
  }
  if (typeof lockUntilMillis === "number" && lockUntilMillis > now) {
    throw new HttpsError("aborted", "پشتیبان‌گیری یا بازیابی استاد در حال انجام است؛ کمی بعد دوباره تلاش کنید.");
  }
}

export function validateRecordType(value: unknown): RecordType {
  if (value === "class" || value === "course" || value === "lecture") return value;
  throw new HttpsError("invalid-argument", "نوع رکورد معتبر نیست.");
}

export function validateRecordId(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new HttpsError("invalid-argument", "شناسه رکورد معتبر نیست.");
  }
  return value;
}

export function sanitizePatch(type: RecordType, input: unknown): Record<string, unknown> {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new HttpsError("invalid-argument", "اطلاعات ویرایش معتبر نیست.");
  }

  const source = input as Record<string, unknown>;
  const allowed = FIELD_TYPES[type];
  const keys = Object.keys(source);

  if (keys.length === 0 || keys.length > 20) {
    throw new HttpsError("invalid-argument", "تعداد فیلدهای ویرایش معتبر نیست.");
  }

  const result: Record<string, unknown> = {};
  for (const key of keys) {
    const expected = allowed[key];
    if (!expected) {
      throw new HttpsError("invalid-argument", `ویرایش فیلد «${key}» مجاز نیست.`);
    }

    const value = source[key];
    const valid =
      (expected === "string" && typeof value === "string" && value.length <= 200_000) ||
      (expected === "number" && typeof value === "number" && Number.isFinite(value)) ||
      (expected === "boolean" && typeof value === "boolean") ||
      (expected === "nullableString" && (value === null || (typeof value === "string" && value.length <= 200_000)));

    if (!valid) {
      throw new HttpsError("invalid-argument", `مقدار فیلد «${key}» معتبر نیست.`);
    }
    result[key] = value;
  }
  return result;
}
