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
const base = (import.meta.env.DARSYAR_API_BASE_URL || "").replace(/\/+$/, "");
async function request<T>(path: string, token: string): Promise<T> {
  if (!base) throw new Error("نشانی سرور تنظیم نشده است.");
  const response = await fetch(`${base}${path}`, {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
  });
  if (!response.ok) {
    if (response.status === 401) throw new Error("نشست ورود معتبر نیست؛ دوباره وارد شوید.");
    if (response.status === 403) throw new Error("اجازه دسترسی به این بخش را ندارید.");
    throw new Error(`خطا در دریافت اطلاعات سرور (${response.status})`);
  }
  return response.json() as Promise<T>;
}
export const api = {
  me: (token: string) => request<Me>("/api/v1/me", token),
  teachers: (token: string) => request<Teacher[]>("/api/v1/teachers", token),
  classes: (token: string) => request<ClassRecord[]>("/api/v1/classes", token),
  sessions: (classId: string, token: string) => request<SessionSummary[]>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions`, token),
  session: (classId: string, sessionId: string, token: string) =>
    request<SessionDetail>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions/${encodeURIComponent(sessionId)}`, token),
};