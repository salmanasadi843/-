export type ClassAnnouncement = {
  id: string; classId: string; authorUserId: string; authorName: string;
  title: string; body: string; isPinned: boolean; publishedAtMs: number;
};
export type ForumTopic = {
  id: string; classId: string; sessionId: string | null; authorUserId: string; authorName: string;
  title: string; body: string; isPinned: boolean; isLocked: boolean; replyCount: number;
  createdAtMs: number; updatedAtMs: number;
};
export type ForumPost = {
  id: string; topicId: string; parentPostId: string | null; authorUserId: string;
  authorName: string; body: string; createdAtMs: number; updatedAtMs: number;
};
export type UserNotification = {
  id: string; notificationType: string; message: string; classId: string | null;
  topicId: string | null; postId: string | null; actorUserId: string | null;
  isRead: boolean; createdAtMs: number;
};
export type Me = { userId: string; roles: string[] };
export type Teacher = { userId: string; displayName: string };
export type CourseRecord = { id: string; classId: string; title: string; teacherName: string; description: string; createdAtMs: number | null; sortOrder: number; version: number };
export type TeacherFolder = { id: string; ownerUserId: string; parentFolderId: string | null; name: string; sortOrder: number; isHidden: boolean; version: number };
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
export type ClassMember = { classId: string; studentUserId: string; status: string; joinedAtMs: number | null; alreadyActive: boolean };
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
export type AudioSource = {
  id: string; sessionId: string; sourceKind: string; sourceUrl: string | null;
  storageKey: string | null; title: string | null; sortOrder: number; isActive: boolean;
};
export type SessionState = {
  userId: string; sessionId: string; isFavorite: boolean;
  lastPlaybackPositionMs: number; updatedAtMs: number;
};
export type SessionNote = {
  id: string; userId: string; sessionId: string; quotedText: string; noteText: string;
  startOffset: number | null; endOffset: number | null; createdAtMs: number; updatedAtMs: number;
};
export type GlobalSessionNote = SessionNote & {
  classId: string; classTitle: string; sessionTitle: string; courseName: string;
  teacherName: string; dateMs: number | null;
};
const base = (import.meta.env.VITE_DARSYAR_API_BASE_URL || "").replace(/\/+$/, "");
async function requestPublic<T>(path: string): Promise<T> {
  if (!base) throw new Error("نشانی سرور تنظیم نشده است.");
  const response = await fetch(`${base}${path}`, { headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`خطا در دریافت محتوای عمومی (${response.status})`);
  return response.json() as Promise<T>;
}
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
  publicClasses: () => requestPublic<ClassRecord[]>("/api/v1/public/classes"),
  publicSessions: (classId: string) => requestPublic<SessionSummary[]>(`/api/v1/public/classes/${encodeURIComponent(classId)}/sessions`),
  publicSession: (classId: string, sessionId: string) =>
    requestPublic<SessionDetail>(`/api/v1/public/classes/${encodeURIComponent(classId)}/sessions/${encodeURIComponent(sessionId)}`),
  me: (token: string) => request<Me>("/api/v1/me", token),
  myNotes: (token: string) => request<GlobalSessionNote[]>("/api/v1/me/notes", token),
  teachers: (token: string) => request<Teacher[]>("/api/v1/teachers", token),
  classes: (token: string) => request<ClassRecord[]>("/api/v1/classes", token),
  teacherFolders: (token: string) => request<TeacherFolder[]>("/api/v1/teacher-folders", token),
  createTeacherFolder: (data: { ownerUserId?: string; parentFolderId?: string | null; name: string; sortOrder?: number; isHidden?: boolean }, token: string) =>
    request<TeacherFolder>("/api/v1/teacher-folders", token, { method: "POST", body: JSON.stringify(data) }),
  updateTeacherFolder: (folderId: string, data: { parentFolderId?: string | null; name: string; sortOrder?: number; isHidden?: boolean }, token: string) =>
    request<TeacherFolder>(`/api/v1/teacher-folders/${encodeURIComponent(folderId)}`, token, { method: "PATCH", body: JSON.stringify(data) }),
  deleteTeacherFolder: (folderId: string, token: string) =>
    request<void>(`/api/v1/teacher-folders/${encodeURIComponent(folderId)}`, token, { method: "DELETE" }),
  createClass: (data: NewClass, token: string) =>
    request<ClassRecord>("/api/v1/classes", token, { method: "POST", body: JSON.stringify(data) }),
  updateClass: (classId: string, data: NewClass, token: string) =>
    request<ClassRecord>(`/api/v1/classes/${encodeURIComponent(classId)}`, token, { method: "PATCH", body: JSON.stringify(data) }),
  deleteClass: (classId: string, token: string) =>
    request<void>(`/api/v1/classes/${encodeURIComponent(classId)}`, token, { method: "DELETE" }),
  deletedClasses: (token: string) => request<ClassRecord[]>("/api/v1/classes/deleted", token),
  restoreClass: (classId: string, token: string) =>
    request<void>(`/api/v1/classes/${encodeURIComponent(classId)}/restore`, token, { method: "POST" }),
  classMembers: (classId: string, token: string) => request<ClassMember[]>(`/api/v1/classes/${encodeURIComponent(classId)}/memberships`, token),
  revokeMembership: (classId: string, studentUserId: string, token: string) =>
    request<void>(`/api/v1/classes/${encodeURIComponent(classId)}/memberships/${encodeURIComponent(studentUserId)}`, token, { method: "DELETE" }),
  courses: (classId: string, token: string) => request<CourseRecord[]>(`/api/v1/classes/${encodeURIComponent(classId)}/courses`, token),
  createCourse: (classId: string, data: { title: string; teacherName?: string; description?: string; createdAtMs?: number | null; sortOrder?: number }, token: string) =>
    request<CourseRecord>(`/api/v1/classes/${encodeURIComponent(classId)}/courses`, token, { method: "POST", body: JSON.stringify(data) }),
  updateCourse: (courseId: string, data: { title: string; teacherName?: string; description?: string; createdAtMs?: number | null; sortOrder?: number }, token: string) =>
    request<CourseRecord>(`/api/v1/courses/${encodeURIComponent(courseId)}`, token, { method: "PATCH", body: JSON.stringify(data) }),
  deleteCourse: (courseId: string, token: string) =>
    request<void>(`/api/v1/courses/${encodeURIComponent(courseId)}`, token, { method: "DELETE" }),
  deletedSessions: (token: string) => request<SessionSummary[]>("/api/v1/sessions/deleted", token),
  sessions: (classId: string, token: string) => request<SessionSummary[]>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions`, token),
  session: (classId: string, sessionId: string, token: string) =>
    request<SessionDetail>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions/${encodeURIComponent(sessionId)}`, token),
  sessionState: (classId: string, sessionId: string, token: string) =>
    request<SessionState>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions/${encodeURIComponent(sessionId)}/state`, token),
  saveSessionState: (classId: string, sessionId: string, data: { isFavorite: boolean; lastPlaybackPositionMs: number }, token: string) =>
    request<SessionState>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions/${encodeURIComponent(sessionId)}/state`, token, { method: "PUT", body: JSON.stringify(data) }),
  sessionNotes: (classId: string, sessionId: string, token: string) =>
    request<SessionNote[]>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions/${encodeURIComponent(sessionId)}/notes`, token),
  createSessionNote: (classId: string, sessionId: string, data: { quotedText?: string; noteText: string; startOffset?: number | null; endOffset?: number | null }, token: string) =>
    request<SessionNote>(`/api/v1/classes/${encodeURIComponent(classId)}/sessions/${encodeURIComponent(sessionId)}/notes`, token, { method: "POST", body: JSON.stringify(data) }),
  updateSessionNote: (noteId: string, data: { quotedText?: string; noteText: string; startOffset?: number | null; endOffset?: number | null }, token: string) =>
    request<SessionNote>(`/api/v1/session-notes/${encodeURIComponent(noteId)}`, token, { method: "PATCH", body: JSON.stringify(data) }),
  deleteSessionNote: (noteId: string, token: string) =>
    request<void>(`/api/v1/session-notes/${encodeURIComponent(noteId)}`, token, { method: "DELETE" }),
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
  audioSources: (sessionId: string, token: string) =>
    request<AudioSource[]>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/audio-sources`, token),
  createAudioSource: (sessionId: string, data: { sourceKind: string; sourceUrl: string; title?: string; sortOrder?: number }, token: string) =>
    request<AudioSource>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/audio-sources`, token, { method: "POST", body: JSON.stringify(data) }),
  deactivateAudioSource: (sessionId: string, sourceId: string, token: string) =>
    request<void>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/audio-sources/${encodeURIComponent(sourceId)}`, token, { method: "DELETE" }),
  forumTopics: (classId: string, token: string) =>
    request<ForumTopic[]>(`/api/v1/classes/${encodeURIComponent(classId)}/forum/topics`, token),
  createForumTopic: (classId: string, data: { sessionId?: string | null; title: string; body: string }, token: string) =>
    request<ForumTopic>(`/api/v1/classes/${encodeURIComponent(classId)}/forum/topics`, token, { method: "POST", body: JSON.stringify(data) }),
  updateForumTopic: (topicId: string, data: { title: string; body: string }, token: string) =>
    request<ForumTopic>(`/api/v1/forum/topics/${encodeURIComponent(topicId)}`, token, { method: "PATCH", body: JSON.stringify(data) }),
  deleteForumTopic: (topicId: string, token: string) =>
    request<void>(`/api/v1/forum/topics/${encodeURIComponent(topicId)}`, token, { method: "DELETE" }),
  forumPosts: (topicId: string, token: string) =>
    request<ForumPost[]>(`/api/v1/forum/topics/${encodeURIComponent(topicId)}/posts`, token),
  createForumReply: (topicId: string, data: { parentPostId?: string | null; body: string }, token: string) =>
    request<ForumPost>(`/api/v1/forum/topics/${encodeURIComponent(topicId)}/posts`, token, { method: "POST", body: JSON.stringify(data) }),
  updateForumPost: (postId: string, body: string, token: string) =>
    request<ForumPost>(`/api/v1/forum/posts/${encodeURIComponent(postId)}`, token, { method: "PATCH", body: JSON.stringify({ body }) }),
  deleteForumPost: (postId: string, token: string) =>
    request<void>(`/api/v1/forum/posts/${encodeURIComponent(postId)}`, token, { method: "DELETE" }),
  moderateForumTopic: (topicId: string, data: { isPinned: boolean; isLocked: boolean }, token: string) =>
    request<void>(`/api/v1/forum/topics/${encodeURIComponent(topicId)}/moderation`, token, { method: "PATCH", body: JSON.stringify(data) }),
  notifications: (unreadOnly: boolean, token: string) =>
    request<UserNotification[]>(`/api/v1/notifications?unreadOnly=${unreadOnly}`, token),
  markNotificationRead: (notificationId: string, token: string) =>
    request<void>(`/api/v1/notifications/${encodeURIComponent(notificationId)}/read`, token, { method: "POST" }),
  classAnnouncements: (classId: string, token: string) =>
    request<ClassAnnouncement[]>(`/api/v1/classes/${encodeURIComponent(classId)}/announcements`, token),
  createClassAnnouncement: (classId: string, data: { title: string; body: string }, token: string) =>
    request<ClassAnnouncement>(`/api/v1/classes/${encodeURIComponent(classId)}/announcements`, token, { method: "POST", body: JSON.stringify(data) }),

};