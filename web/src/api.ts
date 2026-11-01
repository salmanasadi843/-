export type Me = { userId: string; roles: string[] };
export type Teacher = { userId: string; displayName: string };
export type ClassRecord = {
  id: string; ownerUserId: string; title: string; description: string; term: string;
  visibility: "public" | "private"; isPublished: boolean; isHidden: boolean; sortOrder: number; folderId: string | null;
};
export type SessionSummary = {
  id: string; classId: string; courseId: string | null; title: string; courseName: string;
  professorName: string; dateMs: number | null; audioDurationMs: number; tags: string;
  isPublished: boolean; lastEditedMs: number | null; sortOrder: number; version: number;
};
export type SessionDetail = SessionSummary & {
  transcript: string; originalTranscript: string; aiSummary: string | null; aiKeyPoints: string | null;
  aiQuizJson?: string | null;
};
export type NewClass = {
  ownerUserId?: string; title: string; description: string; term: string;
  visibility: "public" | "private"; isPublished: boolean; isHidden: boolean; sortOrder: number; folderId?: string | null;
};
export type NewSession = {
  title: string; courseName: string; professorName: string; dateMs: number;
  transcript: string; originalTranscript: string; aiSummary: string | null; aiKeyPoints: string | null;
  isPublished: boolean; lastEditedMs: number; sortOrder: number;
};
export type Membership = {
  classId: string; studentUserId: string; status: string; joinedAtMs: number | null; alreadyActive: boolean;
};
export type AccessCode = {
  id: string; classId: string; expiresAtMs: number | null; maxUses: number | null;
  useCount: number; isActive: boolean; createdAtMs: number;
};
export type CreatedAccessCode = {
  id: string; classId: string; code: string; expiresAtMs: number | null; maxUses: number | null;
};
const base = (import.meta.env.VITE_DARSYAR_API_BASE_URL || "").replace(/\/+$/, "");
async function request<T>(path: string, token: string, options: RequestInit = {}): Promise<T> {
  if (!base) throw new Error("نشانی سرور تنظیم نشده است.");
  const response = await fetch(`${base}${path}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${token}`, Accept: "application/json",
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...options.headers,
    },
  });
  if (!response.ok) {
    if (response.status === 401) throw new Error("نشست ورود معتبر نیست؛ دوباره وارد شوید.");
    if (response.status === 403) throw new Error("اجازه انجام این عملیات را ندارید.");
    if (response.status === 404) throw new Error("محتوای درخواستی پیدا نشد.");
    throw new Error(`خطا در ارتباط با سرور (${response.status})`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
export const api = {
  me: (token: string) => request<Me>("/api/v1/me", token),
  teachers: (token: string) => request<Teacher[]>("/api/v1/teachers", token),
  classes: (token: string) => request<ClassRecord[]>("/api/v1/classes", token),
  createClass: (data: NewClass, token: string) =>
    request<ClassRecord>("/api/v1/classes", token, { method: "POST", body: JSON.stringify(data) }),
  updateClass: (classId: string, data: NewClass, token: string) =>
    request<ClassRecord>(`/api/v1/classes/${encodeURIComponent(classId)}`, token, { method: "PATCH", body: JSON.stringify(data) }),
  deleteClass: (classId: string, token: string) =>
    request<void>(`/api/v1/classes/${encodeURIComponent(classId)}`, token, { method: "DELETE" }),
  deletedClasses: (token: string) => request<ClassRecord[]>("/api/v1/classes/deleted", token),
  restoreClass: (classId: string, token: string) =>
    request<void>(`/api/v1/classes/${encodeURIComponent(classId)}/restore`, token, { method: "POST" }),
  sessions: (classId: string, token: string) => request<SessionSummary[]>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions`, token),
  session: (classId: string, sessionId: string, token: string) =>
    request<SessionDetail>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions/${encodeURIComponent(sessionId)}`, token),
  createSession: (classId: string, data: NewSession, token: string) =>
    request<SessionDetail>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions`, token, { method: "POST", body: JSON.stringify(data) }),
  updateSession: (sessionId: string, data: NewSession & { courseId?: string | null; audioDurationMs?: number; tags?: string; aiQuizJson?: string | null }, token: string) =>
    request<SessionDetail>(`/api/v1/sessions/${encodeURIComponent(sessionId)}`, token, { method: "PATCH", body: JSON.stringify(data) }),
  deleteSession: (sessionId: string, token: string) =>
    request<void>(`/api/v1/sessions/${encodeURIComponent(sessionId)}`, token, { method: "DELETE" }),
  restoreSession: (sessionId: string, token: string) =>
    request<void>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/restore`, token, { method: "POST" }),
  redeemAccessCode: (code: string, token: string) =>
    request<Membership>("/api/v1/class-memberships/redeem", token, { method: "POST", body: JSON.stringify({ code }) }),
  accessCodes: (classId: string, token: string) =>
    request<AccessCode[]>(`/api/v1/classes/${encodeURIComponent(classId)}/access-codes`, token),
  createAccessCode: (classId: string, data: { expiresAtMs?: number | null; maxUses?: number | null }, token: string) =>
    request<CreatedAccessCode>(`/api/v1/classes/${encodeURIComponent(classId)}/access-codes`, token, { method: "POST", body: JSON.stringify(data) }),
  revokeAccessCode: (codeId: string, token: string) =>
    request<void>(`/api/v1/access-codes/${encodeURIComponent(codeId)}`, token, { method: "DELETE" }),
};