import { FormEvent, MouseEvent, useEffect, useMemo, useState } from "react";
import { onAuthStateChanged, sendPasswordResetEmail, signInWithEmailAndPassword, signOut, User } from "firebase/auth";
import { api, AccessCode, AudioSource, ClassAnnouncement, ClassMember, ClassRecord, CourseRecord, ForumPost, ForumTopic, Me, SessionDetail, SessionNote, GlobalSessionNote, SessionState, SessionSummary, SessionSearchResult, Teacher, TeacherApplication, TeacherFolder, UserNotification } from "./api";
import { auth, firebaseConfigured } from "./firebase";

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [me, setMe] = useState<Me | null>(null);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [folders, setFolders] = useState<TeacherFolder[]>([]);
  const [folderName, setFolderName] = useState("");
  const [parentFolderId, setParentFolderId] = useState("");
  const [selectedFolderId, setSelectedFolderId] = useState("");
  const [browseFolderId, setBrowseFolderId] = useState("all");
  const [showFolderForm, setShowFolderForm] = useState(false);
  const [classes, setClasses] = useState<ClassRecord[]>([]);
  const [publicClasses, setPublicClasses] = useState<ClassRecord[]>([]);
  const [publicClass, setPublicClass] = useState<ClassRecord | null>(null);
  const [publicSessions, setPublicSessions] = useState<SessionSummary[]>([]);
  const [publicDetail, setPublicDetail] = useState<SessionDetail | null>(null);
  const [publicLoading, setPublicLoading] = useState(false);
  const [publicError, setPublicError] = useState("");
  const [publicHighlight, setPublicHighlight] = useState<{ start: number; end: number } | null>(null);
  const [selectedTeacher, setSelectedTeacher] = useState("all");
  const [selectedClass, setSelectedClass] = useState<ClassRecord | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [movingSessionId, setMovingSessionId] = useState<string | null>(null);
  const [courses, setCourses] = useState<CourseRecord[]>([]);
  const [courseTitle, setCourseTitle] = useState("");
  const [courseDescription, setCourseDescription] = useState("");
  const [editingCourseId, setEditingCourseId] = useState<string | null>(null);
  const [showCourseForm, setShowCourseForm] = useState(false);
  const [sessionCourseId, setSessionCourseId] = useState("");
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [announcements, setAnnouncements] = useState<ClassAnnouncement[]>([]);
  const [announcementTitle, setAnnouncementTitle] = useState("");
  const [announcementBody, setAnnouncementBody] = useState("");
  const [forumTopics, setForumTopics] = useState<ForumTopic[]>([]);
  const [activeTopic, setActiveTopic] = useState<ForumTopic | null>(null);
  const [forumPosts, setForumPosts] = useState<ForumPost[]>([]);
  const [highlightedForumPostId, setHighlightedForumPostId] = useState<string | null>(null);
  const [forumTitle, setForumTitle] = useState("");
  const [forumBody, setForumBody] = useState("");
  const [replyBody, setReplyBody] = useState("");
  const [replyParentId, setReplyParentId] = useState<string | null>(null);
  const [editingTopic, setEditingTopic] = useState(false);
  const [editTopicTitle, setEditTopicTitle] = useState("");
  const [editTopicBody, setEditTopicBody] = useState("");
  const [editingPostId, setEditingPostId] = useState<string | null>(null);
  const [editPostBody, setEditPostBody] = useState("");
  const [notifications, setNotifications] = useState<UserNotification[]>([]);
  const [showNotifications, setShowNotifications] = useState(false);
  const [audioSources, setAudioSources] = useState<AudioSource[]>([]);
  const [sessionState, setSessionState] = useState<SessionState | null>(null);
  const [sessionNotes, setSessionNotes] = useState<SessionNote[]>([]);
  const [globalNotes, setGlobalNotes] = useState<GlobalSessionNote[]>([]);
  const [globalNotesSearch, setGlobalNotesSearch] = useState("");
  const [sessionSearchQuery, setSessionSearchQuery] = useState("");
  const [sessionSearchMode, setSessionSearchMode] = useState<"all" | "phrase">("all");
  const [sessionSearchResults, setSessionSearchResults] = useState<SessionSearchResult[]>([]);
  const [sessionSearchLoading, setSessionSearchLoading] = useState(false);
  const [searchHighlightQuery, setSearchHighlightQuery] = useState("");
  const [searchHighlightMode, setSearchHighlightMode] = useState<"all" | "phrase">("all");
  const [searchHighlightField, setSearchHighlightField] = useState("");
  const [showMyNotes, setShowMyNotes] = useState(false);
  const [loadingGlobalNotes, setLoadingGlobalNotes] = useState(false);
  const [pendingNoteFocusId, setPendingNoteFocusId] = useState<string | null>(null);
  const [noteQuote, setNoteQuote] = useState("");
  const [noteDraft, setNoteDraft] = useState("");
  const [noteOffsets, setNoteOffsets] = useState<{ start: number; end: number } | null>(null);
  const [activeNoteId, setActiveNoteId] = useState<string | null>(null);
  const [editingNoteId, setEditingNoteId] = useState<string | null>(null);
  const [playbackPositionMs, setPlaybackPositionMs] = useState(0);
  const [audioTitle, setAudioTitle] = useState("");
  const [audioUrl, setAudioUrl] = useState("");
  const [audioKind, setAudioKind] = useState<"direct_url" | "hosted_page" | "external_link">("direct_url");
  const [showClassForm, setShowClassForm] = useState(false);
  const [showSessionForm, setShowSessionForm] = useState(false);
  const [editingClass, setEditingClass] = useState(false);
  const [editingSession, setEditingSession] = useState(false);
  const [deletedClasses, setDeletedClasses] = useState<ClassRecord[]>([]);
  const [showDeletedClasses, setShowDeletedClasses] = useState(false);
  const [deletedSessions, setDeletedSessions] = useState<SessionSummary[]>([]);
  const [showDeletedSessions, setShowDeletedSessions] = useState(false);
  const [classTitle, setClassTitle] = useState("");
  const [classDescription, setClassDescription] = useState("");
  const [classTerm, setClassTerm] = useState("");
  const [classVisibility, setClassVisibility] = useState<"public" | "private">("private");
  const [sessionTitle, setSessionTitle] = useState("");
  const [sessionTranscript, setSessionTranscript] = useState("");
  const [sessionSummary, setSessionSummary] = useState("");
  const [sessionKeyPoints, setSessionKeyPoints] = useState("");
  const [accessCode, setAccessCode] = useState("");
  const [classAccessCodes, setClassAccessCodes] = useState<AccessCode[]>([]);
  const [classMembers, setClassMembers] = useState<ClassMember[]>([]);
  const [newlyCreatedCode, setNewlyCreatedCode] = useState("");
  const [maxCodeUses, setMaxCodeUses] = useState("20");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [applicationTeachingField, setApplicationTeachingField] = useState("");
  const [applicationInstitution, setApplicationInstitution] = useState("");
  const [pendingTeacherApplications, setPendingTeacherApplications] = useState<TeacherApplication[]>([]);
  const [loadingTeacherApplications, setLoadingTeacherApplications] = useState(false);

  useEffect(() => {
    if (!highlightedForumPostId || !forumPosts.some(post => post.id === highlightedForumPostId)) return;
    document.getElementById(`forum-post-${highlightedForumPostId}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [highlightedForumPostId, forumPosts]);

  useEffect(() => {
    if (!searchHighlightQuery || !detail) return;
    document.getElementById("search-highlight-target")?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [searchHighlightQuery, detail]);

  useEffect(() => {
    if (!auth) return;
    return onAuthStateChanged(auth, setUser);
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      if (!user) { setMe(null); setTeachers([]); setClasses([]); setFolders([]); setNotifications([]); return; }
      setBusy(true); setError("");
      try {
        const token = await user.getIdToken();
        const [profile, teacherList, classList, folderList, inbox] = await Promise.all([
          api.me(token), api.teachers(token), api.classes(token), api.teacherFolders(token), api.notifications(false, token),
        ]);
        if (!cancelled) { setMe(profile); setTeachers(teacherList); setClasses(classList); setFolders(folderList); setNotifications(inbox); }
      } catch (e) { if (!cancelled) setError(message(e)); }
      finally { if (!cancelled) setBusy(false); }
    }
    void load();
    return () => { cancelled = true; };
  }, [user]);

  useEffect(() => {
    let cancelled = false;
    void api.publicClasses().then(items => {
      if (!cancelled) setPublicClasses(items);
    }).catch(e => {
      if (!cancelled) setPublicError(message(e));
    });
    return () => { cancelled = true; };
  }, []);

  async function openPublicClass(item: ClassRecord) {
    setPublicClass(item); setPublicDetail(null); setPublicSessions([]); setPublicHighlight(null);
    setPublicLoading(true); setPublicError("");
    try { setPublicSessions(await api.publicSessions(item.id)); }
    catch (e) { setPublicError(message(e)); }
    finally { setPublicLoading(false); }
  }

  async function openPublicSession(item: SessionSummary) {
    if (!publicClass) return;
    setPublicLoading(true); setPublicError(""); setPublicDetail(null); setPublicHighlight(null);
    try { setPublicDetail(await api.publicSession(publicClass.id, item.id)); }
    catch (e) { setPublicError(message(e)); }
    finally { setPublicLoading(false); }
  }

  const isAdmin = me?.roles.includes("super_admin") ?? false;

  useEffect(() => {
    let cancelled = false;
    async function loadApplications() {
      if (!user || !isAdmin) { setPendingTeacherApplications([]); return; }
      setLoadingTeacherApplications(true);
      try {
        const items = await api.pendingTeacherApplications(await user.getIdToken());
        if (!cancelled) setPendingTeacherApplications(items);
      } catch (e) {
        if (!cancelled) setError(message(e));
      } finally {
        if (!cancelled) setLoadingTeacherApplications(false);
      }
    }
    void loadApplications();
    return () => { cancelled = true; };
  }, [user, isAdmin]);
  const canCreateClass = isAdmin || (me?.roles.includes("teacher") ?? false);
  const canRedeemCode = me?.roles.includes("student") ?? false;
  const browseFolderIds = useMemo(() => {
    if (browseFolderId === "all" || browseFolderId === "none") return null;
    const root = folders.find(folder => folder.id === browseFolderId);
    if (!root) return new Set<string>();
    const ids = new Set<string>([root.id]);
    let changed = true;
    while (changed) {
      changed = false;
      folders.forEach(folder => {
        if (folder.ownerUserId === root.ownerUserId && folder.parentFolderId && ids.has(folder.parentFolderId) && !ids.has(folder.id)) {
          ids.add(folder.id);
          changed = true;
        }
      });
    }
    return ids;
  }, [folders, browseFolderId]);
  const visibleClasses = useMemo(
    () => classes.filter(c =>
      (selectedTeacher === "all" || c.ownerUserId === selectedTeacher) &&
      (browseFolderId === "all" ||
        (browseFolderId === "none" ? !c.folderId : !!c.folderId && !!browseFolderIds?.has(c.folderId))),
    ),
    [classes, selectedTeacher, browseFolderId, browseFolderIds],
  );
  const folderOwnerId = isAdmin ? (selectedTeacher === "all" ? "" : selectedTeacher) : (me?.roles.includes("teacher") ? me.userId : "");
  const visibleFolders = useMemo(() => folders.filter(folder => folder.ownerUserId === folderOwnerId), [folders, folderOwnerId]);
  const canManageSelectedClass = !!selectedClass &&
    (isAdmin || selectedClass.ownerUserId === me?.userId) &&
    (isAdmin || (me?.roles.includes("teacher") ?? false));
  const renderFolderRows = (parentId: string | null, depth = 0) =>
    visibleFolders.filter(folder => folder.parentFolderId === parentId).map(folder => (
      <div key={folder.id}>
        <div className="folder-row" style={{ paddingInlineStart: depth * 18 }}>
          <span>{depth > 0 ? "↳ " : ""}{folder.name}{folder.isHidden ? " · مخفی" : ""}</span>
          <span>
            <button className="ghost" onClick={() => {setBrowseFolderId(folder.id);setSelectedClass(null);setDetail(null);}}>نمایش کلاس‌ها</button>
            <button className="ghost" disabled={busy} onClick={() => void renameTeacherFolder(folder)}>تغییر نام</button>
            <button className="ghost" disabled={busy} onClick={() => void toggleFolderHidden(folder)}>{folder.isHidden ? "نمایش" : "مخفی"}</button>
            <button className="ghost danger-text" disabled={busy} onClick={() => void removeTeacherFolder(folder)}>حذف</button>
          </span>
        </div>
        {renderFolderRows(folder.id, depth + 1)}
      </div>
    ));

  const renderAdminTeacherFolders = (ownerId: string, parentId: string | null = null, depth = 0) =>
    folders.filter(folder => folder.ownerUserId === ownerId && folder.parentFolderId === parentId).map(folder => (
      <div key={folder.id}>
        <div className="folder-row" style={{ paddingInlineStart: depth * 18 }}>
          <span>{depth > 0 ? "↳ " : ""}{folder.name}{folder.isHidden ? " · مخفی" : ""}</span>
          <span>
            <button className="ghost" onClick={() => {setSelectedTeacher(ownerId);setBrowseFolderId(folder.id);setSelectedClass(null);setDetail(null);}}>نمایش کلاس‌ها</button>
            <button className="ghost" disabled={busy} onClick={() => void renameTeacherFolder(folder)}>تغییر نام</button>
            <button className="ghost" disabled={busy} onClick={() => void toggleFolderHidden(folder)}>{folder.isHidden ? "نمایش" : "مخفی"}</button>
            <button className="ghost danger-text" disabled={busy} onClick={() => void removeTeacherFolder(folder)}>حذف</button>
          </span>
        </div>
        {renderAdminTeacherFolders(ownerId, folder.id, depth + 1)}
      </div>
    ));

  async function login(event: FormEvent) {
    event.preventDefault(); if (!auth) return;
    setBusy(true); setError(""); setNotice("");
    try { await signInWithEmailAndPassword(auth, email.trim(), password); setPassword(""); }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function submitTeacherApplication(event: FormEvent) {
    event.preventDefault();
    if (!user) return;
    if (!applicationTeachingField.trim() && !applicationInstitution.trim()) {
      setError("حوزه تدریس یا نام مرکز آموزشی را وارد کنید.");
      return;
    }
    setBusy(true); setError(""); setNotice("");
    try {
      const result = await api.submitTeacherApplication({
        teachingField: applicationTeachingField.trim() || null,
        institutionName: applicationInstitution.trim() || null,
      }, await user.getIdToken());
      setNotice("درخواست همکاری شما ثبت شد؛ وضعیت: " + (result.approvalStatus === "pending" ? "در انتظار بررسی مدیر" : result.approvalStatus) + ".");
      setApplicationTeachingField(""); setApplicationInstitution("");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function decideTeacherApplication(item: TeacherApplication, decision: "approved" | "rejected") {
    if (!user || !isAdmin) return;
    const label = decision === "approved" ? "تأیید" : "رد";
    if (!window.confirm("درخواست " + item.displayName + " " + label + " شود؟")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.decideTeacherApplication(item.userId, decision, await user.getIdToken());
      setPendingTeacherApplications(current => current.filter(application => application.userId !== item.userId));
      if (decision === "approved") {
        setTeachers(await api.teachers(await user.getIdToken()));
        setNotice("درخواست " + item.displayName + " تأیید شد و دسترسی استاد برای او فعال شد.");
      } else {
        setNotice("درخواست " + item.displayName + " رد شد.");
      }
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function resetPassword() {
    if (!auth || !email.trim()) {
      setError("ابتدا ایمیل حساب کاربری را وارد کنید.");
      return;
    }
    setBusy(true); setError(""); setNotice("");
    try {
      await sendPasswordResetEmail(auth, email.trim());
      setNotice("اگر این ایمیل در درس‌یار ثبت شده باشد، پیوند بازنشانی گذرواژه برای آن ارسال می‌شود.");
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  async function openClass(item: ClassRecord) {
    if (!user) return;
    setSelectedClass(item); setDetail(null); setAudioSources([]); setAnnouncements([]); setForumTopics([]); setActiveTopic(null); setForumPosts([]); setSessions([]); setCourses([]); setClassAccessCodes([]); setClassMembers([]); setNewlyCreatedCode(""); setShowSessionForm(false); setBusy(true); setError("");
    try {
      const token = await user.getIdToken();
      const canManage = me?.roles.includes("super_admin") || (me?.roles.includes("teacher") && item.ownerUserId === me.userId);
      const [sessionList, courseList, codes, members, topics, notices] = await Promise.all([
        api.sessions(item.id, token),
        api.courses(item.id, token),
        canManage ? api.accessCodes(item.id, token) : Promise.resolve([] as AccessCode[]),
        canManage ? api.classMembers(item.id, token) : Promise.resolve([] as ClassMember[]),
        api.forumTopics(item.id, token),
        api.classAnnouncements(item.id, token),
      ]);
      setSessions(sessionList); setCourses(courseList); setClassAccessCodes(codes); setClassMembers(members); setForumTopics(topics); setAnnouncements(notices);
    }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function openSession(item: SessionSummary) {
    if (!user || !selectedClass) return;
    setBusy(true); setError(""); setDetail(null);
    try {
      const token = await user.getIdToken();
      const [sessionDetail, sources, savedState, notes] = await Promise.all([
        api.session(selectedClass.id, item.id, token),
        api.audioSources(item.id, token),
        api.sessionState(selectedClass.id, item.id, token),
        api.sessionNotes(selectedClass.id, item.id, token),
      ]);
      setDetail(sessionDetail);
      setAudioSources(sources);
      setSessionState(savedState);
      setPlaybackPositionMs(savedState.lastPlaybackPositionMs);
      setSessionNotes(notes);
      setSearchHighlightQuery(""); setSearchHighlightField("");
      setNoteQuote(""); setNoteDraft(""); setNoteOffsets(null); setActiveNoteId(null); setEditingNoteId(null);
    }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function runSessionSearch(event: FormEvent) {
    event.preventDefault();
    if (!user || sessionSearchQuery.trim().length < 2) return;
    setSessionSearchLoading(true); setError(""); setNotice("");
    try {
      const results = await api.searchSessions(sessionSearchQuery.trim(), sessionSearchMode, await user.getIdToken());
      setSessionSearchResults(results);
      if (!results.length) setNotice("در محتوای قابل‌دسترسی جلسه‌ای با این عبارت پیدا نشد.");
    } catch (e) { setError(message(e)); }
    finally { setSessionSearchLoading(false); }
  }

  async function openSessionSearchResult(result: SessionSearchResult) {
    if (!user) return;
    setBusy(true); setError(""); setNotice(""); setShowMyNotes(false);
    try {
      const token = await user.getIdToken();
      const classList = classes.length ? classes : await api.classes(token);
      const classRecord = classList.find(item => item.id === result.classId);
      if (!classRecord) throw new Error("این کلاس دیگر در فهرست قابل‌دسترسی شما نیست.");
      const [sessionList, courseList, sessionDetail, sources, savedState, notes, topics, notices] = await Promise.all([
        api.sessions(classRecord.id, token),
        api.courses(classRecord.id, token),
        api.session(classRecord.id, result.id, token),
        api.audioSources(result.id, token),
        api.sessionState(classRecord.id, result.id, token),
        api.sessionNotes(classRecord.id, result.id, token),
        api.forumTopics(classRecord.id, token),
        api.classAnnouncements(classRecord.id, token),
      ]);
      setSelectedClass(classRecord); setSessions(sessionList); setCourses(courseList); setDetail(sessionDetail);
      setAudioSources(sources); setSessionState(savedState); setPlaybackPositionMs(savedState.lastPlaybackPositionMs);
      setSessionNotes(notes); setForumTopics(topics); setAnnouncements(notices);
      setActiveTopic(null); setForumPosts([]);
      setSearchHighlightMode(sessionSearchMode);
      setSearchHighlightField(result.matchedIn);
      setSearchHighlightQuery(["متن اصلی", "متن ویرایش‌شده"].includes(result.matchedIn) ? sessionSearchQuery.trim() : "");
      setNoteQuote(""); setNoteDraft(""); setNoteOffsets(null); setActiveNoteId(null); setEditingNoteId(null);
      setNotice("جلسهٔ پیدا‌شده باز شد؛ محل تطبیق در متن مشخص شده است.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  function highlightSearchText(source: string) {
    const normalized = normalizeWebSearchWithOffsets(source);
    const query = normalizeWebSearchText(searchHighlightQuery);
    const terms = searchHighlightMode === "phrase" ? [query] : query.split(/\\s+/).filter(Boolean);
    if (!terms.length || !normalized.text) return source;
    const ranges: Array<{ start: number; end: number }> = [];
    terms.forEach(term => {
      let from = 0;
      while (from < normalized.text.length) {
        const index = normalized.text.indexOf(term, from);
        if (index < 0) break;
        const start = normalized.offsets[index];
        const end = normalized.offsets[index + term.length - 1];
        if (start != null && end != null) ranges.push({ start, end: end + 1 });
        from = index + Math.max(1, term.length);
      }
    });
    ranges.sort((a, b) => a.start - b.start);
    const merged = ranges.reduce<Array<{ start: number; end: number }>>((items, range) => {
      const previous = items[items.length - 1];
      if (previous && range.start <= previous.end) previous.end = Math.max(previous.end, range.end);
      else items.push({ ...range });
      return items;
    }, []);
    if (!merged.length) return source;
    const parts: Array<{ text: string; highlighted: boolean }> = [];
    let cursor = 0;
    merged.forEach(range => {
      if (range.start < cursor) return;
      if (range.start > cursor) parts.push({ text: source.slice(cursor, range.start), highlighted: false });
      parts.push({ text: source.slice(range.start, range.end), highlighted: true });
      cursor = range.end;
    });
    if (cursor < source.length) parts.push({ text: source.slice(cursor), highlighted: false });
    return <>{parts.map((part, index) => part.highlighted
      ? <mark className="session-search-highlight" key={index}>{part.text}</mark>
      : <span key={index}>{part.text}</span>)}</>;
  }

  async function loadGlobalNotes() {
    if (!user) return;
    setLoadingGlobalNotes(true); setError("");
    try {
      const items = await api.myNotes(await user.getIdToken());
      setGlobalNotes(items);
      setShowMyNotes(true);
    } catch (e) { setError(message(e)); }
    finally { setLoadingGlobalNotes(false); }
  }

  async function openGlobalNote(note: GlobalSessionNote, editAfterOpen = false) {
    if (!user) return;
    setBusy(true); setError(""); setShowMyNotes(false);
    try {
      const token = await user.getIdToken();
      const classList = classes.length ? classes : await api.classes(token);
      const classRecord = classList.find(item => item.id === note.classId);
      if (!classRecord) throw new Error("این کلاس دیگر در فهرست قابل‌دسترسی شما نیست.");
      const sessionList = await api.sessions(classRecord.id, token);
      const session = sessionList.find(item => item.id === note.sessionId);
      if (!session) throw new Error("این جلسه دیگر در دسترس نیست.");
      const [sessionDetail, sources, savedState, notes] = await Promise.all([
        api.session(classRecord.id, session.id, token),
        api.audioSources(session.id, token),
        api.sessionState(classRecord.id, session.id, token),
        api.sessionNotes(classRecord.id, session.id, token),
      ]);
      setSelectedClass(classRecord); setSessions(sessionList); setDetail(sessionDetail);
      setAudioSources(sources); setSessionState(savedState); setPlaybackPositionMs(savedState.lastPlaybackPositionMs);
      setSessionNotes(notes); setActiveNoteId(note.id); setPendingNoteFocusId(note.id);
      if (editAfterOpen) editPersonalNote(note);
      else { setNoteQuote(""); setNoteDraft(""); setNoteOffsets(null); setEditingNoteId(null); }
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function deleteGlobalNote(noteId: string) {
    if (!user || !window.confirm("این یادداشت شخصی حذف شود؟")) return;
    setBusy(true); setError("");
    try {
      await api.deleteSessionNote(noteId, await user.getIdToken());
      setGlobalNotes(items => items.filter(item => item.id !== noteId));
      setSessionNotes(items => items.filter(item => item.id !== noteId));
      setNotice("یادداشت حذف شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function persistSessionState(patch: Partial<Pick<SessionState, "isFavorite" | "lastPlaybackPositionMs">>) {
    if (!user || !selectedClass || !detail || !sessionState) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const next = {
        isFavorite: patch.isFavorite ?? sessionState.isFavorite,
        lastPlaybackPositionMs: patch.lastPlaybackPositionMs ?? sessionState.lastPlaybackPositionMs,
      };
      const saved = await api.saveSessionState(selectedClass.id, detail.id, next, await user.getIdToken());
      setSessionState(saved);
      setPlaybackPositionMs(saved.lastPlaybackPositionMs);
      setNotice("وضعیت مطالعه شما ذخیره شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function savePersonalNote(event: FormEvent) {
    event.preventDefault();
    if (!user || !selectedClass || !detail || !noteDraft.trim()) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const token = await user.getIdToken();
      const data = { quotedText: noteQuote.trim(), noteText: noteDraft.trim(), startOffset: noteOffsets?.start ?? null, endOffset: noteOffsets?.end ?? null };
      if (editingNoteId) await api.updateSessionNote(editingNoteId, data, token);
      else await api.createSessionNote(selectedClass.id, detail.id, data, token);
      setSessionNotes(await api.sessionNotes(selectedClass.id, detail.id, token));
      setNoteQuote(""); setNoteDraft(""); setNoteOffsets(null); setActiveNoteId(null); setEditingNoteId(null);
      setNotice("یادداشت شخصی ذخیره شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  function editPersonalNote(note: SessionNote) {
    setEditingNoteId(note.id); setNoteQuote(note.quotedText); setNoteDraft(note.noteText);
    setNoteOffsets(note.startOffset != null && note.endOffset != null ? { start: note.startOffset, end: note.endOffset } : null);
    setActiveNoteId(note.id);
  }
  function capturePublicTranscriptSelection(event: MouseEvent<HTMLPreElement>) {
    const root = event.currentTarget;
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || selection.rangeCount === 0) return;
    const range = selection.getRangeAt(0);
    if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return;
    const before = range.cloneRange();
    before.selectNodeContents(root);
    before.setEnd(range.startContainer, range.startOffset);
    const raw = range.toString();
    const quote = raw.trim();
    if (!quote) return;
    const start = before.toString().length + (raw.length - raw.trimStart().length);
    setPublicHighlight({ start, end: start + quote.length });
  }
  function publicTranscriptWithHighlight() {
    const text = publicDetail?.transcript ?? "";
    if (!publicHighlight || publicHighlight.start < 0 || publicHighlight.end > text.length) return text;
    return <>{text.slice(0, publicHighlight.start)}<mark className="guest-temporary-highlight">{text.slice(publicHighlight.start, publicHighlight.end)}</mark>{text.slice(publicHighlight.end)}</>;
  }
  function captureTranscriptSelection(event: MouseEvent<HTMLParagraphElement>) {
    const root = event.currentTarget;
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || selection.rangeCount === 0) return;
    const range = selection.getRangeAt(0);
    if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return;
    const before = range.cloneRange();
    before.selectNodeContents(root);
    before.setEnd(range.startContainer, range.startOffset);
    const raw = range.toString();
    const quote = raw.trim();
    if (!quote) return;
    const start = before.toString().length + (raw.length - raw.trimStart().length);
    setNoteQuote(quote);
    setNoteOffsets({ start, end: start + quote.length });
    setActiveNoteId(null);
  }
  useEffect(() => {
    if (!pendingNoteFocusId || !detail || !sessionNotes.some(note => note.id === pendingNoteFocusId)) return;
    const note = sessionNotes.find(item => item.id === pendingNoteFocusId);
    if (note) focusPersonalNote(note);
    setPendingNoteFocusId(null);
  }, [pendingNoteFocusId, detail, sessionNotes]);

  function focusPersonalNote(note: SessionNote) {
    setActiveNoteId(note.id);
    if (!detail || !note.quotedText) return;
    const matches = (text: string) => note.startOffset != null && note.endOffset != null && text.slice(note.startOffset, note.endOffset) === note.quotedText
      ? note.startOffset : text.indexOf(note.quotedText);
    const target = matches(detail.originalTranscript ?? "") >= 0 ? "original-transcript"
      : matches(detail.transcript ?? "") >= 0 ? "edited-transcript" : null;
    if (target) requestAnimationFrame(() => document.getElementById(target)?.scrollIntoView({ behavior: "smooth", block: "center" }));
  }
  useEffect(() => {
    if (!detail || !searchHighlightQuery.trim()) return;
    const frame = window.requestAnimationFrame(() => {
      const target = document.querySelector("#search-highlight-target mark.session-search-highlight");
      target?.scrollIntoView({ behavior: "smooth", block: "center" });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [detail, searchHighlightQuery, searchHighlightField, searchHighlightMode]);

  function transcriptWithHighlight(text: string) {
    const note = sessionNotes.find(item => item.id === activeNoteId);
    if (!note?.quotedText) return text;
    const start = note.startOffset != null && note.endOffset != null && text.slice(note.startOffset, note.endOffset) === note.quotedText
      ? note.startOffset : text.indexOf(note.quotedText);
    if (start < 0) return text;
    const end = start + note.quotedText.length;
    return <>{text.slice(0, start)}<mark className="note-anchor-highlight">{text.slice(start, end)}</mark>{text.slice(end)}</>;
  }
  async function deletePersonalNote(noteId: string) {
    if (!user) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.deleteSessionNote(noteId, await user.getIdToken());
      setSessionNotes(current => current.filter(note => note.id !== noteId));
      if (editingNoteId === noteId) { setEditingNoteId(null); setNoteQuote(""); setNoteDraft(""); setNoteOffsets(null); }
      setNotice("یادداشت حذف شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function addAudioSource(event: FormEvent) {
    event.preventDefault();
    if (!user || !detail || !canManageSelectedClass || !audioUrl.trim()) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const created = await api.createAudioSource(detail.id, {
        sourceKind: audioKind, sourceUrl: audioUrl.trim(),
        title: audioTitle.trim() || undefined, sortOrder: audioSources.length,
      }, await user.getIdToken());
      setAudioSources(current => [...current, created]);
      setAudioTitle(""); setAudioUrl("");
      setNotice("پیوند صوتی جلسه اضافه شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function removeAudioSource(sourceId: string) {
    if (!user || !detail || !canManageSelectedClass) return;
    if (!window.confirm("این پیوند از فهرست جلسه غیرفعال شود؟ فایل یا منبع اصلی حذف نمی‌شود.")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.deactivateAudioSource(detail.id, sourceId, await user.getIdToken());
      setAudioSources(current => current.filter(source => source.id !== sourceId));
      setNotice("پیوند صوتی از جلسه برداشته شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function createClassAnnouncement(event: FormEvent) {
    event.preventDefault();
    if (!user || !selectedClass || !canManageSelectedClass || !announcementTitle.trim() || !announcementBody.trim()) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const created = await api.createClassAnnouncement(selectedClass.id, {
        title: announcementTitle.trim(), body: announcementBody.trim(),
      }, await user.getIdToken());
      setAnnouncements(current => [created, ...current]);
      setAnnouncementTitle(""); setAnnouncementBody("");
      await refreshNotifications();
      setNotice("اعلان برای اعضای فعال کلاس منتشر شد.");
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function deleteClassAnnouncement(item: ClassAnnouncement) {
    if (!user || !selectedClass || !canManageSelectedClass) return;
    if (!window.confirm("این اعلان از فهرست کلاس حذف شود؟")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.deleteClassAnnouncement(selectedClass.id, item.id, await user.getIdToken());
      setAnnouncements(current => current.filter(announcement => announcement.id !== item.id));
      setNotice("اعلان حذف شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function openForumTopic(topic: ForumTopic, targetPostId: string | null = null) {
    if (!user) return;
    setBusy(true); setError("");
    try {
      const posts = await api.forumPosts(topic.id, await user.getIdToken());
      setActiveTopic(topic); setForumPosts(posts); setReplyParentId(null); setHighlightedForumPostId(targetPostId);
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function createForumTopic(event: FormEvent) {
    event.preventDefault();
    if (!user || !selectedClass || !forumTitle.trim() || !forumBody.trim()) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const created = await api.createForumTopic(selectedClass.id, {
        title: forumTitle.trim(), body: forumBody.trim(),
        sessionId: detail?.id ?? undefined,
      }, await user.getIdToken());
      setForumTopics(current => [created, ...current]);
      setForumTitle(""); setForumBody("");
      await openForumTopic(created);
      await refreshNotifications();
      setNotice("بحث جدید در تالار کلاس ایجاد شد.");
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function createForumReply(event: FormEvent) {
    event.preventDefault();
    if (!user || !activeTopic || !replyBody.trim() || activeTopic.isLocked) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.createForumReply(activeTopic.id, { parentPostId: replyParentId, body: replyBody.trim() }, await user.getIdToken());
      setReplyBody(""); setReplyParentId(null);
      const [posts, topics] = await Promise.all([
        api.forumPosts(activeTopic.id, await user.getIdToken()),
        api.forumTopics(activeTopic.classId, await user.getIdToken()),
      ]);
      setForumPosts(posts); setForumTopics(topics);
      setActiveTopic(topics.find(t => t.id === activeTopic.id) ?? activeTopic);
      await refreshNotifications();
      setNotice("پاسخ شما ثبت شد.");
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  const canEditForumAuthorContent = (authorUserId: string) =>
    isAdmin || selectedClass?.ownerUserId === me?.userId || authorUserId === me?.userId;

  async function saveForumTopic() {
    if (!user || !activeTopic || !canEditForumAuthorContent(activeTopic.authorUserId)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await api.updateForumTopic(activeTopic.id,
        { title: editTopicTitle.trim(), body: editTopicBody.trim() }, await user.getIdToken());
      setForumTopics(current => current.map(item => item.id === updated.id ? updated : item));
      setActiveTopic(updated); setEditingTopic(false);
      setNotice("بحث ویرایش شد.");
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function deleteForumTopic() {
    if (!user || !activeTopic || !canEditForumAuthorContent(activeTopic.authorUserId)) return;
    if (!window.confirm("این بحث و پاسخ‌های آن از تالار حذف شود؟ امکان بازیابی از این صفحه وجود ندارد.")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.deleteForumTopic(activeTopic.id, await user.getIdToken());
      setForumTopics(current => current.filter(item => item.id !== activeTopic.id));
      setActiveTopic(null); setForumPosts([]); setEditingTopic(false);
      setNotice("بحث حذف شد.");
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function saveForumPost(post: ForumPost) {
    if (!user || !canEditForumAuthorContent(post.authorUserId)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const updated = await api.updateForumPost(post.id, editPostBody.trim(), await user.getIdToken());
      setForumPosts(current => current.map(item => item.id === updated.id ? updated : item));
      setEditingPostId(null); setEditPostBody("");
      setNotice("پاسخ ویرایش شد.");
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function deleteForumPost(post: ForumPost) {
    if (!user || !canEditForumAuthorContent(post.authorUserId)) return;
    if (!window.confirm("این پاسخ از تالار حذف شود؟")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.deleteForumPost(post.id, await user.getIdToken());
      setForumPosts(current => current.filter(item => item.id !== post.id));
      setNotice("پاسخ حذف شد.");
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function moderateForumTopic(topic: ForumTopic, patch: Partial<Pick<ForumTopic,"isPinned"|"isLocked">>) {
    if (!user || !canManageSelectedClass) return;
    setBusy(true); setError("");
    try {
      await api.moderateForumTopic(topic.id, {
        isPinned: patch.isPinned ?? topic.isPinned,
        isLocked: patch.isLocked ?? topic.isLocked,
      }, await user.getIdToken());
      const topics = await api.forumTopics(topic.classId, await user.getIdToken());
      setForumTopics(topics); setActiveTopic(topics.find(t => t.id === topic.id) ?? null);
      setNotice("تنظیمات بحث به‌روزرسانی شد.");
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function refreshNotifications() {
    if (!user) return;
    try { setNotifications(await api.notifications(false, await user.getIdToken())); }
    catch(e) { setError(message(e)); }
  }
  useEffect(() => {
    if (!showNotifications || !user) return;
    const timer = window.setInterval(() => { void refreshNotifications(); }, 60_000);
    return () => window.clearInterval(timer);
  }, [showNotifications, user]);

  async function openNotification(item: UserNotification) {
    if (!user) return;
    setBusy(true); setError("");
    try {
      if (!item.isRead) await api.markNotificationRead(item.id, await user.getIdToken());
      const inbox = await api.notifications(false, await user.getIdToken());
      setNotifications(inbox);
      setShowNotifications(false);
      const clazz = item.classId ? classes.find(c => c.id === item.classId) : null;
      if (clazz) {
        await openClass(clazz);
        if (item.topicId) {
          const token = await user.getIdToken();
          const topic = (await api.forumTopics(clazz.id, token)).find(t => t.id === item.topicId);
          if (topic) {
            await openForumTopic(topic, item.postId);
            if (topic.sessionId) {
              const sessionList = await api.sessions(clazz.id, token);
              const linkedSession = sessionList.find(session => session.id === topic.sessionId);
              if (linkedSession) {
                const [sessionDetail, sources, savedState, notes] = await Promise.all([
                  api.session(clazz.id, linkedSession.id, token),
                  api.audioSources(linkedSession.id, token),
                  api.sessionState(clazz.id, linkedSession.id, token),
                  api.sessionNotes(clazz.id, linkedSession.id, token),
                ]);
                setSessions(sessionList);
                setDetail(sessionDetail);
                setAudioSources(sources);
                setSessionState(savedState);
                setPlaybackPositionMs(savedState.lastPlaybackPositionMs);
                setSessionNotes(notes);
                setNoteQuote(""); setNoteDraft(""); setNoteOffsets(null); setActiveNoteId(null); setEditingNoteId(null);
              }
            }
          }
        }
      }
    } catch(e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function redeemCode(event: FormEvent) {
    event.preventDefault();
    if (!user || !accessCode.trim()) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const token = await user.getIdToken();
      const membership = await api.redeemAccessCode(accessCode.trim(), token);
      const refreshed = await api.classes(token);
      setClasses(refreshed); setAccessCode("");
      setNotice(membership.alreadyActive ? "عضویت شما از قبل فعال بود." : "با موفقیت به کلاس پیوستید.");
      const joinedClass = refreshed.find(c => c.id === membership.classId);
      if (joinedClass) await openClass(joinedClass);
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function revokeClassMember(studentUserId: string) {
    if (!user || !selectedClass || !canManageSelectedClass) return;
    if (!window.confirm("عضویت این دانشجو در کلاس لغو شود؟")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.revokeMembership(selectedClass.id, studentUserId, await user.getIdToken());
      setClassMembers(await api.classMembers(selectedClass.id, await user.getIdToken()));
      setNotice("عضویت دانشجو لغو شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function createClassAccessCode(event: FormEvent) {
    event.preventDefault();
    if (!user || !selectedClass || !canManageSelectedClass) return;
    const uses = Number(maxCodeUses);
    if (!Number.isInteger(uses) || uses < 1 || uses > 10000) {
      setError("تعداد دفعات استفاده باید عددی بین ۱ تا ۱۰۰۰۰ باشد.");
      return;
    }
    setBusy(true); setError(""); setNotice(""); setNewlyCreatedCode("");
    try {
      const created = await api.createAccessCode(selectedClass.id, { maxUses: uses }, await user.getIdToken());
      setNewlyCreatedCode(created.code);
      setClassAccessCodes(await api.accessCodes(selectedClass.id, await user.getIdToken()));
      setNotice("کد ورود ساخته شد؛ آن را اکنون در اختیار دانشجویان قرار دهید.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function revokeClassAccessCode(codeId: string) {
    if (!user || !selectedClass || !canManageSelectedClass) return;
    if (!window.confirm("این کد غیرفعال شود؟ دانشجویان دیگر نمی‌توانند با آن عضو شوند.")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.revokeAccessCode(codeId, await user.getIdToken());
      setClassAccessCodes(await api.accessCodes(selectedClass.id, await user.getIdToken()));
      setNotice("کد ورود غیرفعال شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function createTeacherFolder(event: FormEvent) {
    event.preventDefault();
    if (!user || !folderOwnerId || !folderName.trim()) return;
    setBusy(true); setError("");
    try {
      const created = await api.createTeacherFolder({ ...(isAdmin ? { ownerUserId: folderOwnerId } : {}), parentFolderId: parentFolderId || null, name: folderName.trim(), sortOrder: visibleFolders.length }, await user.getIdToken());
      setFolders(current => [...current, created]); setFolderName(""); setParentFolderId(""); setShowFolderForm(false); setNotice("پوشه استاد ایجاد شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function renameTeacherFolder(folder: TeacherFolder) {
    if (!user) return;
    const nextName = window.prompt("نام جدید پوشه را وارد کنید:", folder.name)?.trim();
    if (!nextName || nextName === folder.name) return;
    setBusy(true); setError("");
    try {
      const updated = await api.updateTeacherFolder(folder.id, { name: nextName, parentFolderId: folder.parentFolderId, sortOrder: folder.sortOrder, isHidden: folder.isHidden }, await user.getIdToken());
      setFolders(current => current.map(item => item.id === updated.id ? updated : item));
      setNotice("نام پوشه تغییر کرد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function toggleFolderHidden(folder: TeacherFolder) {
    if (!user) return;
    setBusy(true); setError("");
    try {
      const updated = await api.updateTeacherFolder(folder.id, { name: folder.name, parentFolderId: folder.parentFolderId, sortOrder: folder.sortOrder, isHidden: !folder.isHidden }, await user.getIdToken());
      setFolders(current => current.map(item => item.id === updated.id ? updated : item));
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function removeTeacherFolder(folder: TeacherFolder) {
    if (!user || !window.confirm("این پوشه حذف شود؟ پوشه باید خالی باشد.")) return;
    setBusy(true); setError("");
    try {
      await api.deleteTeacherFolder(folder.id, await user.getIdToken());
      setFolders(current => current.filter(item => item.id !== folder.id));
      if (selectedFolderId === folder.id) setSelectedFolderId("");
      setNotice("پوشه حذف شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function saveCourse(event: FormEvent) {
    event.preventDefault();
    if (!user || !selectedClass || !canManageSelectedClass || !courseTitle.trim()) return;
    setBusy(true); setError("");
    try {
      const data = { title: courseTitle.trim(), teacherName: teachers.find(t => t.userId === selectedClass.ownerUserId)?.displayName ?? "استاد", description: courseDescription.trim(), createdAtMs: Date.now(), sortOrder: editingCourseId ? courses.find(c => c.id === editingCourseId)?.sortOrder ?? 0 : courses.length };
      if (editingCourseId) await api.updateCourse(editingCourseId, data, await user.getIdToken());
      else await api.createCourse(selectedClass.id, data, await user.getIdToken());
      setCourses(await api.courses(selectedClass.id, await user.getIdToken()));
      setCourseTitle(""); setCourseDescription(""); setEditingCourseId(null); setShowCourseForm(false); setNotice("دوره ذخیره شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function removeCourse(course: CourseRecord) {
    if (!user || !window.confirm("این دوره حذف شود؟ دوره دارای جلسه قابل حذف نیست.")) return;
    setBusy(true); setError("");
    try {
      await api.deleteCourse(course.id, await user.getIdToken());
      setCourses(await api.courses(selectedClass!.id, await user.getIdToken()));
      setNotice("دوره حذف شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function editSelectedClass() {
    if (!selectedClass || !canManageSelectedClass) return;
    setClassTitle(selectedClass.title); setClassDescription(selectedClass.description);
    setClassTerm(selectedClass.term); setClassVisibility(selectedClass.visibility); setSelectedFolderId(selectedClass.folderId ?? "");
    setEditingClass(true); setShowClassForm(true); setError("");
  }
  async function removeSelectedClass() {
    if (!user || !selectedClass || !canManageSelectedClass) return;
    if (!window.confirm("این کلاس و محتوای آن به‌صورت نرم‌افزاری حذف شود؟ امکان بازیابی وجود دارد.")) return;
    setBusy(true); setError("");
    try {
      await api.deleteClass(selectedClass.id, await user.getIdToken());
      setClasses(await api.classes(await user.getIdToken()));
      setSelectedClass(null); setSessions([]); setDetail(null); setNotice("کلاس حذف شد و در بخش بازیابی قرار گرفت.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function loadDeletedClasses() {
    if (!user || !isAdmin) return;
    setBusy(true); setError("");
    try { setDeletedClasses(await api.deletedClasses(await user.getIdToken())); setShowDeletedClasses(true); }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function restoreDeletedClass(classId: string) {
    if (!user || !isAdmin) return;
    setBusy(true); setError("");
    try {
      await api.restoreClass(classId, await user.getIdToken());
      const token = await user.getIdToken();
      setClasses(await api.classes(token)); setDeletedClasses(await api.deletedClasses(token));
      setNotice("کلاس بازیابی شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function loadDeletedSessions() {
    if (!user || !canCreateClass) return;
    setBusy(true); setError("");
    try {
      setDeletedSessions(await api.deletedSessions(await user.getIdToken()));
      setShowDeletedSessions(true);
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function restoreDeletedSession(sessionId: string) {
    if (!user || !canCreateClass) return;
    const target = deletedSessions.find(item => item.id === sessionId);
    if (!target) return;
    setBusy(true); setError("");
    try {
      const token = await user.getIdToken();
      await api.restoreSession(sessionId, token);
      setDeletedSessions(await api.deletedSessions(token));
      if (selectedClass?.id === target.classId) {
        setSessions(await api.sessions(target.classId, token));
      }
      setNotice("جلسه بازیابی شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function editSelectedSession() {
    if (!detail || !canManageSelectedClass) return;
    setSessionTitle(detail.title); setSessionTranscript(detail.transcript); setSessionCourseId(detail.courseId ?? "");
    setSessionSummary(detail.aiSummary ?? ""); setSessionKeyPoints(detail.aiKeyPoints ?? "");
    setEditingSession(true); setShowSessionForm(true); setError("");
  }
  async function moveSessionToClass(session: SessionSummary, targetClassId: string) {
    if (!user || !canManageSelectedClass || !selectedClass) return;
    const target = classes.find(item => item.id === targetClassId);
    if (!target || target.id === selectedClass.id) return;
    if (!window.confirm(`جلسه «${session.title}» به کلاس «${target.title}» منتقل شود؟ متن‌ها، خلاصه‌ها، صوت‌ها و شناسه جلسه حفظ می‌شوند.`)) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const token = await user.getIdToken();
      await api.moveSession(session.id, target.id, token);
      setSessions(await api.sessions(selectedClass.id, token));
      if (detail?.id === session.id) setDetail(null);
      setMovingSessionId(null);
      setNotice(`جلسه به کلاس «${target.title}» منتقل شد.`);
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function reorderSession(session: SessionSummary, direction: "up" | "down") {
    if (!user || !canManageSelectedClass || !selectedClass) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const token = await user.getIdToken();
      await api.reorderSession(session.id, direction, token);
      setSessions(await api.sessions(selectedClass.id, token));
      setNotice("ترتیب جلسه‌ها به‌روزرسانی شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function reorderClass(item: ClassRecord, siblings: ClassRecord[], direction: "up" | "down") {
    if (!user || !(isAdmin || (me?.roles.includes("teacher") && item.ownerUserId === me.userId))) return;
    const ordered = [...siblings].sort((a, b) => a.sortOrder - b.sortOrder || a.title.localeCompare(b.title, "fa"));
    const index = ordered.findIndex(candidate => candidate.id === item.id);
    const target = ordered[index + (direction === "up" ? -1 : 1)];
    if (!target || busy) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const token = await user.getIdToken();
      await api.reorderClass(item.id, direction, token);
      setClasses(await api.classes(token));
      setNotice("ترتیب کلاس‌ها به‌روزرسانی شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  function compareClassBrowseOrder(a: ClassRecord, b: ClassRecord) {
    const folderName = (item: ClassRecord) => item.folderId
      ? folders.find(folder => folder.id === item.folderId)?.name ?? ""
      : "";
    return folderName(a).localeCompare(folderName(b), "fa") ||
      (a.folderId ?? "").localeCompare(b.folderId ?? "") ||
      a.sortOrder - b.sortOrder || a.title.localeCompare(b.title, "fa");
  }

  function renderClassCard(item: ClassRecord, siblings: ClassRecord[]) {
    const ordered = [...siblings].sort((a, b) => a.sortOrder - b.sortOrder || a.title.localeCompare(b.title, "fa"));
    const index = ordered.findIndex(candidate => candidate.id === item.id);
    const canReorder = isAdmin || (me?.roles.includes("teacher") && item.ownerUserId === me.userId);
    return <div key={item.id} style={{ display: "flex", alignItems: "stretch", gap: 6 }}>
      <button className={selectedClass?.id === item.id ? "class-card selected" : "class-card"} style={{ flex: 1 }} onClick={() => void openClass(item)}>
        <span className="tag">{item.visibility === "public" ? "عمومی" : "خصوصی"}{!item.isPublished ? " · پیش‌نویس" : ""}</span><strong>{item.title}</strong><small className="muted">{item.folderId ? folders.find(folder => folder.id === item.folderId)?.name ?? "پوشه" : "بدون پوشه"}</small>
      </button>
      {canReorder && <div style={{ display: "flex", flexDirection: "column", justifyContent: "center" }}>
        <button type="button" className="ghost" aria-label={`انتقال کلاس ${item.title} به بالا`} title="انتقال به بالا" disabled={busy || index <= 0} onClick={() => void reorderClass(item, siblings, "up")}>↑</button>
        <button type="button" className="ghost" aria-label={`انتقال کلاس ${item.title} به پایین`} title="انتقال به پایین" disabled={busy || index < 0 || index >= ordered.length - 1} onClick={() => void reorderClass(item, siblings, "down")}>↓</button>
      </div>}
    </div>;
  }

  async function removeSelectedSession() {
    if (!user || !detail || !canManageSelectedClass) return;
    if (!window.confirm("این جلسه حذف شود؟ امکان بازیابی آن از طریق API وجود دارد.")) return;
    setBusy(true); setError("");
    try {
      await api.deleteSession(detail.id, await user.getIdToken());
      setSessions(await api.sessions(selectedClass!.id, await user.getIdToken()));
      setDetail(null); setNotice("جلسه حذف شد.");
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  async function createClass(event: FormEvent) {
    event.preventDefault();
    if (!user || !me) return;
    if (isAdmin && selectedTeacher === "all") {
      setError("برای ساخت کلاس توسط مدیر، ابتدا یک استاد را انتخاب کنید.");
      return;
    }
    setBusy(true); setError(""); setNotice("");
    try {
      const data = {
        ...(isAdmin ? { ownerUserId: selectedTeacher } : {}),
        title: classTitle.trim(), description: classDescription.trim(), term: classTerm.trim(),
        visibility: classVisibility, isPublished: editingClass ? selectedClass?.isPublished ?? true : true,
        isHidden: editingClass ? selectedClass?.isHidden ?? false : false,
        sortOrder: editingClass ? selectedClass?.sortOrder ?? 0 : 0,
        folderId: selectedFolderId || null,
      };
      const saved = editingClass && selectedClass
        ? await api.updateClass(selectedClass.id, data, await user.getIdToken())
        : await api.createClass(data, await user.getIdToken());
      setClasses(current => editingClass ? current.map(item => item.id === saved.id ? saved : item) : [...current, saved]);
      setClassTitle(""); setClassDescription(""); setClassTerm(""); setClassVisibility("private"); setSelectedFolderId("");
      setShowClassForm(false); setEditingClass(false); setNotice(editingClass ? "تغییرات کلاس ذخیره شد." : "کلاس با موفقیت ایجاد شد.");
      setSelectedTeacher(saved.ownerUserId);
      await openClass(saved);
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function createSession(event: FormEvent) {
    event.preventDefault();
    if (!user || !selectedClass || !canManageSelectedClass) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const now = Date.now();
      const teacherName = teachers.find(t => t.userId === selectedClass.ownerUserId)?.displayName ?? user.displayName ?? "استاد";
      const payload = {
        title: sessionTitle.trim(), courseId: sessionCourseId || null, courseName: courses.find(c => c.id === sessionCourseId)?.title ?? "", professorName: detail?.professorName ?? teacherName,
        dateMs: detail?.dateMs ?? now, transcript: sessionTranscript,
        originalTranscript: detail?.originalTranscript ?? sessionTranscript,
        aiSummary: sessionSummary.trim() || null, aiKeyPoints: sessionKeyPoints.trim() || null,
        isPublished: detail?.isPublished ?? true, lastEditedMs: now, sortOrder: detail?.sortOrder ?? sessions.length,
        audioDurationMs: detail?.audioDurationMs ?? 0,
        tags: detail?.tags ?? "", aiQuizJson: detail?.aiQuizJson ?? null,
      };
      if (editingSession && detail) await api.updateSession(detail.id, payload, await user.getIdToken());
      else await api.createSession(selectedClass.id, payload, await user.getIdToken());
      setSessionTitle(""); setSessionTranscript(""); setSessionSummary(""); setSessionKeyPoints(""); setSessionCourseId("");
      setShowSessionForm(false); setEditingSession(false); setNotice(editingSession ? "تغییرات جلسه ذخیره شد." : "جلسه با موفقیت ایجاد شد.");
      const token = await user.getIdToken();
      setSessions(await api.sessions(selectedClass.id, token));
      if (editingSession && detail) setDetail(await api.session(selectedClass.id, detail.id, token));
    } catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }

  if (!firebaseConfigured) return <main className="center"><section className="panel narrow">
    <Brand /><h1>راه‌اندازی اولیه سایت</h1>
    <p>برای فعال شدن ورود، تنظیمات عمومی Firebase Web App و نشانی Backend را در فایل <code>web/.env.local</code> وارد کنید. هیچ کلید خصوصی یا Service Account نباید در این فایل قرار بگیرد.</p>
    <p className="muted">این نسخه به سرور عملیاتی متصل نیست و تا زمان تنظیم متغیرها، اطلاعات واقعی نمایش داده نمی‌شود.</p>
  </section></main>;

  if (!user) return <main className="center"><div className="entry-layout">
    <form className="panel narrow" onSubmit={login}>
      <Brand /><h1>ورود به درس‌یار</h1><p className="muted">با حساب کاربری فعلی درس‌یار وارد شوید.</p>
      <label>ایمیل<input id="login-email" type="email" autoComplete="username" value={email} onChange={e => setEmail(e.target.value)} required /></label>
      <label>گذرواژه<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required /></label>
      {error && <p className="error">{error}</p>}
      {notice && <p className="success">{notice}</p>}
      <button disabled={busy}>{busy ? "در حال ورود…" : "ورود"}</button>
      <button type="button" className="ghost" disabled={busy} onClick={() => void resetPassword()}>فراموشی گذرواژه</button>
    </form>
    <section className="panel narrow public-catalog">
      <h2>کلاس‌های عمومی</h2>
      <p className="muted">برای مشاهده این کلاس‌ها و جلسه‌های منتشرشده، نیازی به عضویت یا ورود نیست.</p>
      {publicError && <p className="error">{publicError}</p>}
      {publicClass ? <>
        <button className="ghost" onClick={() => {setPublicClass(null);setPublicSessions([]);setPublicDetail(null);}}>بازگشت به فهرست کلاس‌ها</button>
        <h3>{publicClass.title}</h3>
        {publicClass.description && <p>{publicClass.description}</p>}
        {publicLoading && <p className="muted">در حال دریافت…</p>}
        {publicDetail ? <>
          <button className="ghost" onClick={() => setPublicDetail(null)}>بازگشت به جلسه‌ها</button>
          <h3>{publicDetail.title}</h3>
          {publicDetail.aiSummary && <><h4>خلاصه</h4><p>{publicDetail.aiSummary}</p></>}
          {publicDetail.aiKeyPoints && <><h4>نکات کلیدی</h4><p>{publicDetail.aiKeyPoints}</p></>}
          <h4>متن جلسه</h4>
          <p className="muted">می‌توانید بخشی از متن را انتخاب کنید تا در همین نشست به‌صورت موقت هایلایت شود؛ این هایلایت ذخیره نمی‌شود.</p>
          <pre className="transcript" onMouseUp={capturePublicTranscriptSelection}>{publicTranscriptWithHighlight()}</pre>
          {publicHighlight && <button type="button" className="ghost" onClick={() => setPublicHighlight(null)}>پاک‌کردن هایلایت موقت</button>}
          <p className="muted">هایلایت این صفحه فقط موقت است. برای پرسیدن سؤال از هوش مصنوعی یا ذخیره هایلایت، یادداشت و نشانک باید وارد حساب کاربری شوید.</p>
          <button type="button" className="ghost" onClick={() => { setNotice("برای ادامه، ایمیل حساب خود را وارد کنید و سپس دکمه ورود را بزنید."); document.getElementById("login-email")?.scrollIntoView({ behavior: "smooth", block: "center" }); document.getElementById("login-email")?.focus(); }}>ورود برای استفاده از امکانات تعاملی</button>
        </> : publicSessions.map(session => <button className="list-item" key={session.id} onClick={() => void openPublicSession(session)}>{session.title}</button>)}
        {!publicLoading && !publicSessions.length && !publicDetail && <p className="muted">هنوز جلسه عمومی منتشرشده‌ای در این کلاس نیست.</p>}
      </> : <>
        {publicClasses.map(item => <button className="list-item" key={item.id} onClick={() => void openPublicClass(item)}>
          <strong>{item.title}</strong>{item.description && <small>{item.description}</small>}
        </button>)}
        {!publicClasses.length && !publicError && <p className="muted">فعلاً کلاس عمومی منتشرشده‌ای وجود ندارد.</p>}
      </>}
    </section>
  </div></main>;

  return <main className={showMyNotes ? "shell notes-mode" : "shell"}>
    <header className="topbar"><Brand /><div className="account">{user && <button className="ghost" onClick={() => showMyNotes ? setShowMyNotes(false) : void loadGlobalNotes()}>{showMyNotes ? "بازگشت به کلاس‌ها" : loadingGlobalNotes ? "در حال دریافت یادداشت‌ها…" : "یادداشت‌های من"}</button>}<div className="notification-wrap"><button className="ghost notification-button" onClick={() => {setShowNotifications(v => !v);if(!showNotifications) void refreshNotifications();}}>اعلان‌ها {notifications.filter(n => !n.isRead).length ? <b className="notification-count">{notifications.filter(n => !n.isRead).length}</b> : null}</button>{showNotifications && <section className="notification-popover"><div className="section-head"><strong>اعلان‌ها</strong><button className="ghost" onClick={() => void refreshNotifications()}>تازه‌سازی</button></div>{notifications.slice(0,30).map(n => <button key={n.id} className={n.isRead ? "notification-item read" : "notification-item"} onClick={() => void openNotification(n)}><span>{n.message}</span><small>{new Date(n.createdAtMs).toLocaleString("fa-IR")}</small></button>)}{!notifications.length && <p className="muted">اعلانی ندارید.</p>}</section>}</div><span>{user.email}</span><button className="ghost" onClick={() => void signOut(auth!)}>خروج</button></div></header>
    <section className="welcome"><div><p className="eyebrow">محیط آموزشی درس‌یار</p><h1>{isAdmin ? "مدیریت و مرور محتوا" : "کلاس‌های آموزشی"}</h1><p>ساختار استاد ← کلاس ← جلسه</p></div><span className="role">{roleLabel(me?.roles ?? [])}</span></section>
    <section className="panel session-search-panel">
      <form className="session-search-form" onSubmit={runSessionSearch}>
        <label htmlFor="session-search-input">جست‌وجو در متن همهٔ جلسه‌های قابل‌دسترسی</label>
        <div className="session-search-controls">
          <input id="session-search-input" value={sessionSearchQuery} onChange={event => setSessionSearchQuery(event.target.value)}
            placeholder="عبارت یا واژهٔ موردنظر را بنویسید…" minLength={2} maxLength={200} />
          <select aria-label="حالت جست‌وجو" value={sessionSearchMode} onChange={event => setSessionSearchMode(event.target.value as "all" | "phrase")}>
            <option value="all">همهٔ واژه‌ها</option><option value="phrase">عبارت دقیق</option>
          </select>
          <button disabled={sessionSearchLoading || sessionSearchQuery.trim().length < 2}>{sessionSearchLoading ? "در حال جست‌وجو…" : "جست‌وجو"}</button>
        </div>
        <small className="muted">جست‌وجو فقط در کلاس‌ها و جلسه‌هایی انجام می‌شود که دسترسی مشاهدهٔ آن‌ها را دارید.</small>
      </form>
      {!!sessionSearchResults.length && <div className="session-search-results">
        <div className="section-head"><strong>{sessionSearchResults.length} نتیجهٔ جست‌وجو</strong><button type="button" className="ghost" onClick={() => {setSessionSearchResults([]);setSearchHighlightQuery("");}}>پاک‌کردن نتایج</button></div>
        {sessionSearchResults.map(result => <button type="button" className="session-search-result" key={result.id}
          onClick={() => void openSessionSearchResult(result)}>
          <strong>{result.title}</strong>
          <span>{result.classTitle} · {result.professorName}{result.courseName ? " · " + result.courseName : ""}</span>
          <small>تطبیق در: {result.matchedIn}</small>
          <p>{result.excerpt}</p>
        </button>)}
        {sessionSearchResults.length === 50 && <small className="muted">برای جلوگیری از بار زیاد، حداکثر ۵۰ نتیجه نمایش داده می‌شود؛ عبارت دقیق‌تری وارد کنید.</small>}
      </div>}
    </section>
    {error && <p className="error notice">{error}</p>}
    {notice && <p className="success notice">{notice}</p>}
    {showMyNotes && <section className="panel content global-notes-panel">
      <div className="section-head"><div><h2>یادداشت‌های من</h2><p className="muted">یادداشت‌های شخصی شما در همه کلاس‌ها و جلسه‌های قابل‌دسترسی</p></div><span className="muted">{globalNotes.length} یادداشت</span></div>
      <input className="global-notes-search" value={globalNotesSearch} onChange={event => setGlobalNotesSearch(event.target.value)} placeholder="جست‌وجو در یادداشت، عبارت، استاد یا کلاس…" />
      {globalNotes.filter(note => [note.noteText,note.quotedText,note.classTitle,note.sessionTitle,note.courseName,note.teacherName].join(" ").toLocaleLowerCase().includes(globalNotesSearch.trim().toLocaleLowerCase())).map(note => <article className="global-note-row" key={note.id}>
        <div className="global-note-content">
          <div className="global-note-meta"><strong>{note.classTitle}</strong><span>{note.teacherName}</span><span>{note.sessionTitle}</span></div>
          {note.quotedText && <blockquote className="pre">{note.quotedText}</blockquote>}
          <p className="pre">{note.noteText}</p>
          <small className="muted">{note.courseName ? note.courseName + " · " : ""}{new Date(note.updatedAtMs).toLocaleDateString("fa-IR")}</small>
        </div>
        <div className="global-note-actions"><button className="ghost" onClick={() => void openGlobalNote(note)}>رفتن به جلسه و متن</button><button className="ghost" onClick={() => void openGlobalNote(note, true)}>ویرایش</button><button className="ghost danger-text" disabled={busy} onClick={() => void deleteGlobalNote(note.id)}>حذف</button></div>
      </article>)}
      {!globalNotes.length && <p className="muted empty">هنوز یادداشتی ثبت نکرده‌اید.</p>}
      {!!globalNotes.length && !globalNotes.some(note => [note.noteText,note.quotedText,note.classTitle,note.sessionTitle,note.courseName,note.teacherName].join(" ").toLocaleLowerCase().includes(globalNotesSearch.trim().toLocaleLowerCase())) && <p className="muted empty">نتیجه‌ای برای این جست‌وجو پیدا نشد.</p>}
    </section>}
    {!isAdmin && !(me?.roles.includes("teacher") ?? false) && <section className="panel content teacher-application-panel">
      <div className="section-head"><div><h2>درخواست همکاری به‌عنوان استاد</h2><p className="muted">پس از ثبت درخواست، مدیر آن را بررسی می‌کند و دسترسی تدریس فقط پس از تأیید فعال می‌شود.</p></div></div>
      <form className="editor-form" onSubmit={submitTeacherApplication}>
        <label>حوزه تدریس<input value={applicationTeachingField} onChange={event => setApplicationTeachingField(event.target.value)} maxLength={200} placeholder="مثلاً فقه و اصول، ادبیات…" /></label>
        <label>نام مرکز آموزشی یا مؤسسه (اختیاری)<input value={applicationInstitution} onChange={event => setApplicationInstitution(event.target.value)} maxLength={200} placeholder="نام مدرسه، دانشگاه یا مؤسسه" /></label>
        <button disabled={busy || (!applicationTeachingField.trim() && !applicationInstitution.trim())}>{busy ? "در حال ثبت…" : "ثبت درخواست همکاری"}</button>
      </form>
    </section>}
    {isAdmin && <section className="panel content teacher-applications-panel">
      <div className="section-head"><div><h2>درخواست‌های همکاری استادان</h2><p className="muted">با تأیید درخواست، نقش استاد برای حساب کاربر فعال می‌شود.</p></div><span className="muted">{pendingTeacherApplications.length} درخواست در انتظار</span></div>
      {loadingTeacherApplications && <p className="muted">در حال دریافت درخواست‌ها…</p>}
      {pendingTeacherApplications.map(item => <article className="code-row" key={item.userId}>
        <span><strong>{item.displayName || "کاربر بدون نام"}</strong><small>{item.email || "بدون ایمیل"} · {item.teachingField || "حوزه تدریس مشخص نشده"}{item.institutionName ? " · " + item.institutionName : ""}</small></span>
        <span><button className="action" disabled={busy} onClick={() => void decideTeacherApplication(item, "approved")}>تأیید</button><button className="ghost danger-text" disabled={busy} onClick={() => void decideTeacherApplication(item, "rejected")}>رد درخواست</button></span>
      </article>)}
      {!loadingTeacherApplications && !pendingTeacherApplications.length && <p className="muted">درخواست در انتظار بررسی وجود ندارد.</p>}
    </section>}
    <div className="layout">
      <aside className="panel sidebar">
        {canRedeemCode && <form className="join-form" onSubmit={redeemCode}>
          <h2>عضویت در کلاس خصوصی</h2>
          <label>کد ورود کلاس<input value={accessCode} onChange={e => setAccessCode(e.target.value)} placeholder="کد ورود را وارد کنید" required /></label>
          <button disabled={busy || !accessCode.trim()}>{busy ? "در حال بررسی…" : "ورود به کلاس"}</button>
        </form>}
        <h2>استادان</h2>
        <button className={selectedTeacher === "all" ? "choice active" : "choice"} onClick={() => {setSelectedTeacher("all");setBrowseFolderId("all");setSelectedClass(null);setDetail(null);}}>همه استادان</button>
        {teachers.map(t => <button key={t.userId} className={selectedTeacher === t.userId ? "choice active" : "choice"} onClick={() => {setSelectedTeacher(t.userId);setBrowseFolderId("all");setSelectedClass(null);setDetail(null);}}>{t.displayName}</button>)}
        {(folderOwnerId || (!isAdmin && me?.roles.includes("teacher"))) && <section className="folder-manager">
          <div className="section-head"><h2>پوشه‌های استاد</h2><button className="ghost" onClick={() => setShowFolderForm(v => !v)}>{showFolderForm ? "بستن" : "＋ پوشه"}</button></div>
          {showFolderForm && <form onSubmit={createTeacherFolder} className="folder-form"><input value={folderName} onChange={e => setFolderName(e.target.value)} placeholder="نام پوشه جدید" required /><select value={parentFolderId} onChange={e => setParentFolderId(e.target.value)}><option value="">پوشه اصلی</option>{visibleFolders.filter(folder => !folder.isHidden).map(folder => <option key={folder.id} value={folder.id}>{folder.name}</option>)}</select><button disabled={busy || !folderName.trim()}>ایجاد</button></form>}
          {renderFolderRows(null)}
          {!visibleFolders.length && <p className="muted">پوشه‌ای برای این استاد ثبت نشده است.</p>}
        </section>}
        {isAdmin && selectedTeacher === "all" && <p className="muted">برای مدیریت پوشه‌ها، یک استاد را انتخاب کنید.</p>}
      </aside>
      <section className="panel content">
        <div className="section-head"><h2>کلاس‌ها</h2><span className="muted">{visibleClasses.length} کلاس</span></div>
        {canCreateClass && <div className="toolbar"><button className="action" onClick={() => {setEditingClass(false);setShowClassForm(v => !v);setError("");}}>{showClassForm ? "بستن فرم" : "＋ ایجاد کلاس"}</button>{isAdmin && <button className="ghost" onClick={() => void (showDeletedClasses ? setShowDeletedClasses(false) : loadDeletedClasses())}>{showDeletedClasses ? "بستن بازیابی کلاس‌ها" : "بازیابی کلاس‌های حذف‌شده"}</button>}<button className="ghost" onClick={() => void (showDeletedSessions ? setShowDeletedSessions(false) : loadDeletedSessions())}>{showDeletedSessions ? "بستن بازیابی جلسه‌ها" : "بازیابی جلسه‌های حذف‌شده"}</button>{isAdmin && selectedTeacher === "all" && <span className="muted">برای ساخت کلاس، ابتدا استاد موردنظر را انتخاب کنید.</span>}</div>}
        {showDeletedClasses && isAdmin && <section className="access-manager"><div className="section-head"><h3>کلاس‌های حذف‌شده</h3><span className="muted">{deletedClasses.length} کلاس</span></div>{deletedClasses.map(c => <div className="code-row" key={c.id}><span><strong>{c.title}</strong><small>{teachers.find(t => t.userId === c.ownerUserId)?.displayName ?? "استاد"}</small></span><button className="ghost" disabled={busy} onClick={() => void restoreDeletedClass(c.id)}>بازیابی</button></div>)}{!deletedClasses.length && <p className="muted">کلاس حذف‌شده‌ای وجود ندارد.</p>}</section>}
        {showDeletedSessions && <section className="access-manager"><div className="section-head"><h3>جلسه‌های حذف‌شده</h3><span className="muted">{deletedSessions.length} جلسه</span></div>{deletedSessions.map(s => <div className="code-row" key={s.id}><span><strong>{s.title}</strong><small>{classes.find(c => c.id === s.classId)?.title ?? "کلاس بازیابی‌شده"}</small></span><button className="ghost" disabled={busy} onClick={() => void restoreDeletedSession(s.id)}>بازیابی</button></div>)}{!deletedSessions.length && <p className="muted">جلسه حذف‌شده‌ای برای بازیابی وجود ندارد. جلسه‌های کلاس‌های حذف‌شده پس از بازیابی کلاس در این فهرست ظاهر می‌شوند.</p>}</section>}
        {showClassForm && <form className="editor-form" onSubmit={createClass}>
          <h3>{editingClass ? "ویرایش کلاس" : "کلاس جدید"}</h3>
          <label>عنوان کلاس<input value={classTitle} onChange={e => setClassTitle(e.target.value)} required /></label>
          <label>توضیحات<textarea value={classDescription} onChange={e => setClassDescription(e.target.value)} rows={2} /></label>
          <label>ترم یا سال تحصیلی<input value={classTerm} onChange={e => setClassTerm(e.target.value)} /></label>
          <label>دسترسی<select value={classVisibility} onChange={e => setClassVisibility(e.target.value as "public" | "private")}><option value="public">عمومی ـ بدون نیاز به عضویت</option><option value="private">خصوصی ـ نیازمند عضویت</option></select></label>
          <label>پوشه استاد<select value={selectedFolderId} onChange={e => setSelectedFolderId(e.target.value)}><option value="">بدون پوشه</option>{visibleFolders.filter(folder => !folder.isHidden || folder.id === selectedFolderId).map(folder => <option key={folder.id} value={folder.id}>{folder.name}{folder.isHidden ? " (مخفی)" : ""}</option>)}</select></label>
          <button disabled={busy || !classTitle.trim() || (isAdmin && selectedTeacher === "all")}>{busy ? "در حال ذخیره…" : editingClass ? "ذخیره تغییرات" : "ذخیره کلاس"}</button>
        </form>}
<div className="folder-filter"><button className={browseFolderId === "all" ? "choice active" : "choice"} onClick={() => setBrowseFolderId("all")}>همه پوشه‌ها</button><button className={browseFolderId === "none" ? "choice active" : "choice"} onClick={() => setBrowseFolderId("none")}>بدون پوشه</button>{visibleFolders.filter(folder => !folder.isHidden).map(folder => <button key={folder.id} className={browseFolderId === folder.id ? "choice active" : "choice"} onClick={() => setBrowseFolderId(folder.id)}>{folder.name}</button>)}</div>
        {selectedTeacher !== "all" && <section className="teacher-class-group selected-teacher-heading"><div className="section-head"><h3>استاد: {teachers.find(teacher => teacher.userId === selectedTeacher)?.displayName ?? "استاد انتخاب‌شده"}</h3><span className="muted">کلاس‌های این استاد</span></div></section>}
        {selectedTeacher === "all" ? <div className="teacher-class-groups">
          {teachers.map(teacher => {
            const teacherClasses = visibleClasses.filter(c => c.ownerUserId === teacher.userId);
            return <section className="teacher-class-group" key={teacher.userId}>
              <div className="section-head">
                <h3>استاد: {teacher.displayName}</h3>
                <span className="muted">{teacherClasses.length} کلاس</span>
                {isAdmin && <button className="ghost" onClick={() => {setSelectedTeacher(teacher.userId);setBrowseFolderId("all");setSelectedClass(null);setDetail(null);}}>مدیریت استاد</button>}
              </div>
              {isAdmin && folders.some(folder => folder.ownerUserId === teacher.userId) && (
                <div className="admin-teacher-folders">
                  <strong className="muted">پوشه‌های استاد</strong>
                  {renderAdminTeacherFolders(teacher.userId)}
                </div>
              )}
              {teacherClasses.length ? <div className="class-grid">
                {[...teacherClasses].sort(compareClassBrowseOrder).map(c => renderClassCard(c, teacherClasses.filter(sibling => sibling.folderId === c.folderId)))}
              </div> : <p className="muted">هنوز کلاسی برای این استاد ثبت نشده است.</p>}
            </section>;
          })}
          {!visibleClasses.length && <p className="muted empty">کلاسی برای نمایش وجود ندارد.</p>}
        </div> : <div className="class-grid">
          {[...visibleClasses].sort(compareClassBrowseOrder).map(c => renderClassCard(c, visibleClasses.filter(sibling => sibling.ownerUserId === c.ownerUserId && sibling.folderId === c.folderId)))}
          {!visibleClasses.length && <p className="muted empty">کلاسی برای نمایش وجود ندارد.</p>}
        </div>}
        {selectedClass && <div className="lower">
          <div className="section-head"><div><p className="eyebrow">{teachers.find(teacher => teacher.userId === selectedClass.ownerUserId)?.displayName ?? "استاد"} ← کلاس</p><h2>جلسه‌های {selectedClass.title}</h2></div><span className="muted">{sessions.length} جلسه</span></div>
          {isAdmin && selectedClass.ownerUserId !== me?.userId && <p className="notice">حالت مدیر: امکان مدیریت کامل این کلاس و جلسه‌های استاد دیگر فعال است.</p>}
          {!isAdmin && selectedClass.ownerUserId !== me?.userId && <p className="muted readonly-notice">این کلاس متعلق به استاد دیگری است؛ محتوای منتشرشده را فقط می‌توانید مشاهده کنید.</p>}
          {canManageSelectedClass && <section className="access-manager">
            <div className="section-head"><h3>دوره‌های کلاس</h3><button className="ghost" onClick={() => {setEditingCourseId(null);setCourseTitle("");setCourseDescription("");setShowCourseForm(v => !v);}}>＋ افزودن دوره</button></div>
            {showCourseForm && <form className="editor-form" onSubmit={saveCourse}>
              <h3>{editingCourseId ? "ویرایش دوره" : "دوره جدید"}</h3>
              <label>عنوان دوره<input value={courseTitle} onChange={e => setCourseTitle(e.target.value)} required /></label>
              <label>توضیحات<textarea value={courseDescription} onChange={e => setCourseDescription(e.target.value)} rows={2} /></label>
              <button disabled={busy || !courseTitle.trim()}>ذخیره دوره</button>
            </form>}
            {courses.map(course => <div className="code-row" key={course.id}>
              <span><strong>{course.title}</strong><small>{course.description || "بدون توضیح"}</small></span>
              <span><button className="ghost" disabled={busy} onClick={() => {setEditingCourseId(course.id);setCourseTitle(course.title);setCourseDescription(course.description);setShowCourseForm(true);}}>ویرایش</button><button className="ghost danger-text" disabled={busy} onClick={() => void removeCourse(course)}>حذف</button></span>
            </div>)}
            {!courses.length && <p className="muted">دوره‌ای برای این کلاس ثبت نشده است.</p>}
          </section>}
          {canManageSelectedClass && <div className="toolbar"><button className="ghost" onClick={() => void editSelectedClass()}>ویرایش کلاس</button><button className="ghost danger-text" disabled={busy} onClick={() => void removeSelectedClass()}>حذف کلاس</button></div>}
          {canManageSelectedClass && <div className="toolbar"><button className="action" onClick={() => {setEditingSession(false);setShowSessionForm(v => !v);}}>{showSessionForm ? "بستن فرم" : "＋ افزودن جلسه"}</button></div>}
          {showSessionForm && canManageSelectedClass && <form className="editor-form" onSubmit={createSession}>
            <h3>{editingSession ? "ویرایش جلسه" : "جلسه جدید"}</h3>
            <label>عنوان جلسه<input value={sessionTitle} onChange={e => setSessionTitle(e.target.value)} required /></label>
            <label>دوره<select value={sessionCourseId} onChange={e => setSessionCourseId(e.target.value)}><option value="">بدون دوره</option>{courses.map(course => <option key={course.id} value={course.id}>{course.title}</option>)}</select></label>
            <label>متن جلسه<textarea value={sessionTranscript} onChange={e => setSessionTranscript(e.target.value)} rows={5} /></label>
            <label>خلاصه هوشمند (اختیاری)<textarea value={sessionSummary} onChange={e => setSessionSummary(e.target.value)} rows={3} /></label>
            <label>نکات کلیدی (اختیاری)<textarea value={sessionKeyPoints} onChange={e => setSessionKeyPoints(e.target.value)} rows={3} /></label>
            <button disabled={busy || !sessionTitle.trim()}>{busy ? "در حال ذخیره…" : editingSession ? "ذخیره تغییرات" : "ذخیره جلسه"}</button>
          </form>}
          {canManageSelectedClass && <section className="access-manager">
            <div className="section-head"><h3>اعضای کلاس</h3><span className="muted">{classMembers.filter(m => m.status === "active").length} عضو فعال</span></div>
            {classMembers.map(member => <div className="code-row" key={member.studentUserId}>
              <span><strong>دانشجو</strong><small dir="ltr">{member.studentUserId.length > 14 ? `${member.studentUserId.slice(0, 8)}…${member.studentUserId.slice(-4)}` : member.studentUserId}</small></span>
              <span className="muted">{member.joinedAtMs ? new Date(member.joinedAtMs).toLocaleDateString("fa-IR") : member.status}</span>
              {member.status === "active" && <button className="ghost danger-text" disabled={busy} onClick={() => void revokeClassMember(member.studentUserId)}>لغو عضویت</button>}
            </div>)}
            {!classMembers.length && <p className="muted">هنوز عضوی برای این کلاس ثبت نشده است.</p>}
          </section>}
          {canManageSelectedClass && selectedClass.visibility === "private" && <section className="access-manager">
            <div className="section-head"><h3>کدهای ورود کلاس خصوصی</h3><span className="muted">{classAccessCodes.filter(code => code.isActive).length} کد فعال</span></div>
            <form className="inline-code-form" onSubmit={createClassAccessCode}>
              <label>حداکثر دفعات استفاده<input type="number" min="1" max="10000" value={maxCodeUses} onChange={e => setMaxCodeUses(e.target.value)} required /></label>
              <button disabled={busy}>{busy ? "در حال ساخت…" : "ساخت کد ورود"}</button>
            </form>
            {newlyCreatedCode && <div className="new-code">
              <strong>کد جدید (فقط همین بار نمایش داده می‌شود)</strong>
              <code dir="ltr">{newlyCreatedCode}</code>
              <button type="button" className="ghost" onClick={() => { void navigator.clipboard?.writeText(newlyCreatedCode); setNotice("درخواست کپی کد انجام شد."); }}>کپی کد</button>
            </div>}
            {classAccessCodes.map(code => <div className="code-row" key={code.id}>
              <span><strong>{code.isActive ? "فعال" : "غیرفعال"}</strong><small>{code.useCount} از {code.maxUses ?? "نامحدود"} استفاده</small></span>
              <span className="muted">{code.expiresAtMs ? new Date(code.expiresAtMs).toLocaleDateString("fa-IR") : "بدون انقضا"}</span>
              {code.isActive && <button type="button" className="ghost danger-text" disabled={busy} onClick={() => void revokeClassAccessCode(code.id)}>غیرفعال‌کردن</button>}
            </div>)}
          </section>}
          {sessions.map((s, index) => <div key={s.id} className="session-row">
            <button type="button" className="session-row-open" onClick={() => void openSession(s)}><span>{s.title}</span><span className="muted">{s.courseName}</span></button>
            {canManageSelectedClass && <div className="session-row-actions">
              <button type="button" className="ghost" disabled={busy || index === 0} aria-label="انتقال جلسه به بالا" title="انتقال به بالا" onClick={() => void reorderSession(s, "up")}>↑</button>
              <button type="button" className="ghost" disabled={busy || index === sessions.length - 1} aria-label="انتقال جلسه به پایین" title="انتقال به پایین" onClick={() => void reorderSession(s, "down")}>↓</button>
              <button type="button" className="ghost" disabled={busy} onClick={() => setMovingSessionId(current => current === s.id ? null : s.id)}>انتقال به کلاس دیگر</button>
            </div>}
            {movingSessionId === s.id && canManageSelectedClass && <div className="session-move-targets">
              <span className="muted">کلاس مقصد:</span>
              {classes.filter(target => target.id !== selectedClass.id && (isAdmin || target.ownerUserId === me?.userId)).map(target =>
                <button type="button" className="ghost" key={target.id} disabled={busy} onClick={() => void moveSessionToClass(s, target.id)}>{target.title}</button>
              )}
              {!classes.some(target => target.id !== selectedClass.id && (isAdmin || target.ownerUserId === me?.userId)) && <span className="muted">کلاس مقصد دیگری در دسترس نیست.</span>}
              <button type="button" className="ghost" onClick={() => setMovingSessionId(null)}>انصراف</button>
            </div>}
          </div>)}
          {!sessions.length && !busy && <p className="muted">جلسه‌ای ثبت نشده است.</p>}\n          <section className="announcement-panel">
            <div className="section-head"><div><h3>اعلان‌های رسمی کلاس</h3><p className="muted">اطلاعیه‌های استاد برای اعضای کلاس</p></div><span className="muted">{announcements.length} اعلان</span></div>
            {canManageSelectedClass && <form className="editor-form" onSubmit={createClassAnnouncement}>
              <label>عنوان اعلان<input value={announcementTitle} onChange={e => setAnnouncementTitle(e.target.value)} maxLength={200} placeholder="مثلاً تغییر زمان جلسه" required /></label>
              <label>متن اعلان<textarea value={announcementBody} onChange={e => setAnnouncementBody(e.target.value)} rows={3} maxLength={20000} placeholder="متن اطلاعیه را بنویسید…" required /></label>
              <button disabled={busy || !announcementTitle.trim() || !announcementBody.trim()}>انتشار اعلان</button>
            </form>}
            {announcements.map(item => <article className="announcement-item" key={item.id}>
              <strong>{item.title}</strong><p className="pre">{item.body}</p>
              <small>{item.authorName} · {new Date(item.publishedAtMs).toLocaleString("fa-IR")}</small>
              {canManageSelectedClass && <button type="button" className="ghost danger-text" disabled={busy} onClick={() => void deleteClassAnnouncement(item)}>حذف اعلان</button>}
            </article>)}
            {!announcements.length && <p className="muted">هنوز اعلانی برای این کلاس منتشر نشده است.</p>}
          </section>
          <section className="forum-panel">
            <div className="section-head"><div><h3>تالار گفت‌وگوی کلاس</h3><p className="muted">پرسش‌ها، پاسخ‌ها و گفت‌وگوهای مرتبط با درس</p></div><span className="muted">{forumTopics.length} بحث</span></div>
            <form className="editor-form forum-form" onSubmit={createForumTopic}>
              <h4>{detail ? `بحث درباره جلسه: ${detail.title}` : "ایجاد بحث جدید در کلاس"}</h4>
              <label>عنوان بحث<input value={forumTitle} onChange={e => setForumTitle(e.target.value)} maxLength={200} placeholder="موضوع پرسش یا گفت‌وگو" required /></label>
              <label>متن پیام<textarea value={forumBody} onChange={e => setForumBody(e.target.value)} rows={3} maxLength={20000} placeholder="متن پرسش یا توضیح خود را بنویسید…" required /></label>
              <button disabled={busy || !forumTitle.trim() || !forumBody.trim()}>ثبت بحث</button>
            </form>
            <div className="forum-topics">
              {forumTopics.map(topic => <button key={topic.id} className={activeTopic?.id === topic.id ? "forum-topic active" : "forum-topic"} onClick={() => void openForumTopic(topic)}>
                <span className="forum-topic-flags">{topic.isPinned ? "📌 سنجاق‌شده · " : ""}{topic.isLocked ? "🔒 بسته · " : ""}{topic.sessionId ? "مرتبط با جلسه" : "گفت‌وگوی عمومی کلاس"}</span>
                <strong>{topic.title}</strong><span className="muted">{topic.authorName} · {topic.replyCount} پاسخ</span>
              </button>)}
              {!forumTopics.length && <p className="muted">هنوز بحثی در این تالار ایجاد نشده است.</p>}
            </div>
            {activeTopic && <article className="forum-thread">
              <div className="section-head"><div><h4>{activeTopic.title}</h4><span className="muted">ایجادکننده: {activeTopic.authorName}{activeTopic.updatedAtMs > activeTopic.createdAtMs ? " · ویرایش‌شده" : ""}</span></div><span>
                {canEditForumAuthorContent(activeTopic.authorUserId) && <><button className="ghost" disabled={busy} onClick={() => {setEditTopicTitle(activeTopic.title);setEditTopicBody(activeTopic.body);setEditingTopic(true);}}>ویرایش بحث</button><button className="ghost danger-text" disabled={busy} onClick={() => void deleteForumTopic()}>حذف بحث</button></>}
                {canManageSelectedClass && <><button className="ghost" disabled={busy} onClick={() => void moderateForumTopic(activeTopic,{isPinned:!activeTopic.isPinned})}>{activeTopic.isPinned ? "برداشتن سنجاق" : "سنجاق بحث"}</button><button className="ghost" disabled={busy} onClick={() => void moderateForumTopic(activeTopic,{isLocked:!activeTopic.isLocked})}>{activeTopic.isLocked ? "بازکردن بحث" : "بستن بحث"}</button></>}
              </span></div>
              {editingTopic ? <form className="editor-form" onSubmit={e => {e.preventDefault();void saveForumTopic();}}>
                <label>عنوان بحث<input value={editTopicTitle} onChange={e => setEditTopicTitle(e.target.value)} maxLength={200} required /></label>
                <label>متن بحث<textarea value={editTopicBody} onChange={e => setEditTopicBody(e.target.value)} rows={4} maxLength={20000} required /></label>
                <div><button disabled={busy || !editTopicTitle.trim() || !editTopicBody.trim()}>ذخیره تغییرات</button><button type="button" className="ghost" onClick={() => setEditingTopic(false)}>انصراف</button></div>
              </form> : <p className="pre forum-topic-body">{activeTopic.body}</p>}
              <h4>پاسخ‌ها ({forumPosts.length})</h4>
              {forumPosts.map(post => <div id={`forum-post-${post.id}`} key={post.id} className={`${post.parentPostId ? "forum-post nested" : "forum-post"}${highlightedForumPostId === post.id ? " notification-target" : ""}`}>
                <div className="forum-post-meta"><strong>{post.authorName}</strong><small>{new Date(post.createdAtMs).toLocaleString("fa-IR")}{post.updatedAtMs > post.createdAtMs ? " · ویرایش‌شده" : ""}</small></div>
                {editingPostId === post.id ? <form className="editor-form" onSubmit={e => {e.preventDefault();void saveForumPost(post);}}>
                  <textarea value={editPostBody} onChange={e => setEditPostBody(e.target.value)} rows={3} maxLength={20000} required />
                  <div><button disabled={busy || !editPostBody.trim()}>ذخیره پاسخ</button><button type="button" className="ghost" onClick={() => setEditingPostId(null)}>انصراف</button></div>
                </form> : <p className="pre">{post.body}</p>}
                {canEditForumAuthorContent(post.authorUserId) && editingPostId !== post.id && <><button className="ghost" disabled={busy} onClick={() => {setEditingPostId(post.id);setEditPostBody(post.body);}}>ویرایش</button><button className="ghost danger-text" disabled={busy} onClick={() => void deleteForumPost(post)}>حذف</button></>}
                {!activeTopic.isLocked && <button className="ghost" onClick={() => {setReplyParentId(post.id);setReplyBody("");}}>پاسخ به این پیام</button>}
              </div>)}
              {!activeTopic.isLocked ? <form className="editor-form forum-reply-form" onSubmit={createForumReply}>
                <h4>{replyParentId ? "پاسخ به پیام انتخاب‌شده" : "ثبت پاسخ"}</h4>
                {replyParentId && <button type="button" className="ghost" onClick={() => setReplyParentId(null)}>لغو انتخاب پاسخ</button>}
                <textarea value={replyBody} onChange={e => setReplyBody(e.target.value)} rows={3} maxLength={20000} placeholder="پاسخ خود را بنویسید…" required />
                <button disabled={busy || !replyBody.trim()}>ارسال پاسخ</button>
              </form> : <p className="muted">این بحث توسط مدیر یا استاد مسئول بسته شده است.</p>}
            </article>}
          </section>
        </div>}
        {detail && <article className="detail">
          <div className="toolbar"><h2>{detail.title}</h2>{canManageSelectedClass && <><button className="ghost" onClick={() => void editSelectedSession()}>ویرایش جلسه</button><button className="ghost danger-text" disabled={busy} onClick={() => void removeSelectedSession()}>حذف جلسه</button></>}</div>
          <section className="access-manager">
            <div className="section-head"><h3>وضعیت مطالعه من</h3><span className="muted">فقط برای حساب شما</span></div>
            {sessionState && <>
              <div className="toolbar">
                <strong>{sessionState.isFavorite ? "★ این جلسه نشان شده است" : "☆ این جلسه نشان نشده است"}</strong>
                <button className="ghost" disabled={busy} onClick={() => void persistSessionState({ isFavorite: !sessionState.isFavorite })}>
                  {sessionState.isFavorite ? "برداشتن نشان" : "نشان‌کردن جلسه"}
                </button>
              </div>
              <label>موقعیت شنیدن: {formatDuration(playbackPositionMs)}
                <input type="range" min="0" max={detail.audioDurationMs > 0 ? detail.audioDurationMs : 604800000}
                  step="1000" value={Math.min(playbackPositionMs, detail.audioDurationMs > 0 ? detail.audioDurationMs : 604800000)}
                  onChange={event => setPlaybackPositionMs(Number(event.target.value))} />
              </label>
              <button className="ghost" disabled={busy} onClick={() => void persistSessionState({ lastPlaybackPositionMs: playbackPositionMs })}>ذخیره موقعیت مطالعه</button>
            </>}
          </section>
          <section className="access-manager">
            <div className="section-head"><h3>یادداشت‌های شخصی من</h3><span className="muted">{sessionNotes.length} یادداشت · فقط برای شما</span></div>
            {sessionNotes.map(note => <article className={`code-row ${activeNoteId === note.id ? "personal-note-active" : ""}`} key={note.id}>
              <div>
                {note.quotedText && <blockquote className="pre">{note.quotedText}</blockquote>}
                <p className="pre">{note.noteText}</p>
              </div>
              <span>
                <button className="ghost" type="button" onClick={() => focusPersonalNote(note)}>رفتن به متن</button>
                <button className="ghost" type="button" onClick={() => editPersonalNote(note)}>ویرایش</button>
                <button className="ghost danger-text" type="button" disabled={busy} onClick={() => void deletePersonalNote(note.id)}>حذف</button>
              </span>
            </article>)}
            <form className="editor-form" onSubmit={savePersonalNote}>
              <h4>{editingNoteId ? "ویرایش یادداشت" : "افزودن یادداشت شخصی"}</h4>
              <label>عبارت مرتبط از متن درس (اختیاری)<textarea value={noteQuote} onChange={e => { setNoteQuote(e.target.value); setNoteOffsets(null); }} rows={2} maxLength={10000} placeholder="عبارت یا بخش مهم درس را اینجا وارد کنید…" /></label>
              {noteOffsets && <small className="muted">جایگاه عبارت در متن ذخیره می‌شود ({noteOffsets.start} تا {noteOffsets.end}).</small>}
              <label>یادداشت شما<textarea value={noteDraft} onChange={e => setNoteDraft(e.target.value)} rows={3} maxLength={20000} placeholder="نکته، پرسش یا توضیح خود را بنویسید…" required /></label>
              <span>
                <button disabled={busy || !noteDraft.trim()}>{busy ? "در حال ذخیره…" : editingNoteId ? "ذخیره ویرایش" : "ذخیره یادداشت"}</button>
                {editingNoteId && <button type="button" className="ghost" onClick={() => { setEditingNoteId(null); setNoteQuote(""); setNoteDraft(""); setNoteOffsets(null); setActiveNoteId(null); }}>انصراف</button>}
              </span>
            </form>
          </section>
          <section className="access-manager">
            <div className="section-head"><h3>فایل‌ها و پیوندهای صوتی</h3><span className="muted">{audioSources.length} منبع</span></div>
            {audioSources.map(source => <div className="code-row" key={source.id}>
              <span><strong>{source.title || "منبع صوتی جلسه"}</strong><small>{source.sourceKind === "direct_url" ? "پخش مستقیم" : source.sourceKind === "hosted_page" ? "صفحه میزبان" : "پیوند خارجی"}</small></span>
              <span>
                {source.sourceUrl && <a href={source.sourceUrl} target="_blank" rel="noreferrer">بازکردن</a>}
                {canManageSelectedClass && <button className="ghost danger-text" disabled={busy} onClick={() => void removeAudioSource(source.id)}>برداشتن</button>}
              </span>
              {source.sourceKind === "direct_url" && source.sourceUrl && <audio
                controls preload="none" src={source.sourceUrl}
                onTimeUpdate={event => setPlaybackPositionMs(Math.floor(event.currentTarget.currentTime * 1000))}
                onPause={event => void persistSessionState({ lastPlaybackPositionMs: Math.floor(event.currentTarget.currentTime * 1000) })}
                onLoadedMetadata={event => {
                  const saved = sessionState?.lastPlaybackPositionMs ?? 0;
                  if (saved > 0 && saved < event.currentTarget.duration * 1000) event.currentTarget.currentTime = saved / 1000;
                }}
              />}
            </div>)}
            {!audioSources.length && <p className="muted">برای این جلسه پیوند صوتی ثبت نشده است.</p>}
            {canManageSelectedClass && <form className="editor-form" onSubmit={addAudioSource}>
              <h3>افزودن پیوند صوتی (بدون بارگذاری فایل)</h3>
              <label>عنوان پیوند<input value={audioTitle} onChange={e => setAudioTitle(e.target.value)} placeholder="مثلاً صوت جلسه اول" /></label>
              <label>نشانی اینترنتی صوت یا صفحه میزبان<input type="url" dir="ltr" value={audioUrl} onChange={e => setAudioUrl(e.target.value)} placeholder="https://…" required /></label>
              <label>نوع پیوند<select value={audioKind} onChange={e => setAudioKind(e.target.value as typeof audioKind)}><option value="direct_url">پخش مستقیم صوت</option><option value="hosted_page">صفحه میزبان صوت</option><option value="external_link">پیوند خارجی</option></select></label>
              <button disabled={busy || !audioUrl.trim()}>{busy ? "در حال ذخیره…" : "افزودن پیوند"}</button>
            </form>}
          </section>
          {detail.aiSummary && <><h3>خلاصه هوشمند</h3><p className="pre">{detail.aiSummary}</p></>}
          {detail.aiKeyPoints && <><h3>نکات کلیدی</h3><p className="pre">{detail.aiKeyPoints}</p></>}
          {detail.originalTranscript && <><h3>متن اصلی</h3><p id={searchHighlightQuery && searchHighlightField === "متن اصلی" ? "search-highlight-target" : "original-transcript"} className="pre" onMouseUp={captureTranscriptSelection}>{searchHighlightQuery ? highlightSearchText(detail.originalTranscript) : transcriptWithHighlight(detail.originalTranscript)}</p><p className="muted">بخشی از متن را انتخاب کنید تا همراه یادداشت ذخیره شود.</p></>}
          {detail.transcript && detail.transcript !== detail.originalTranscript && <><h3>متن ویرایش‌شده</h3><p id={searchHighlightQuery && searchHighlightField === "متن ویرایش‌شده" ? "search-highlight-target" : "edited-transcript"} className="pre" onMouseUp={captureTranscriptSelection}>{searchHighlightQuery ? highlightSearchText(detail.transcript) : transcriptWithHighlight(detail.transcript)}</p><p className="muted">بخشی از متن را انتخاب کنید تا همراه یادداشت ذخیره شود.</p></>}
        </article>}
      </section>
    </div>
    <footer>نسخه آزمایشی سایت · مدیریت کلاس‌ها، جلسه‌ها و پوشه‌های استاد در حال تکمیل است</footer>
  </main>;
}
function Brand() { return <div className="brand"><span className="brand-mark">د</span><span>درس‌یار</span></div>; }
function message(e: unknown) { return e instanceof Error ? e.message : "خطای پیش‌بینی‌نشده رخ داد."; }
function formatDuration(milliseconds: number) {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}` : `${minutes}:${String(seconds).padStart(2, "0")}`;
}
function roleLabel(roles: string[]) {
  if (roles.includes("super_admin")) return "مدیر اصلی";
  if (roles.includes("teacher")) return "استاد";
  if (roles.includes("student")) return "دانشجو";
  return "کاربر";
}
function normalizeWebSearchWithOffsets(source: string): { text: string; offsets: number[] } {
  let text = "";
  const offsets: number[] = [];
  let pendingSpace: number | null = null;
  let sourceOffset = 0;
  Array.from(source).forEach(original => {
    const index = sourceOffset;
    sourceOffset += original.length;
    const code = original.codePointAt(0) ?? 0;
    let mapped = original.toLocaleLowerCase();
    if (mapped === "ي") mapped = "ی";
    else if (mapped === "ك") mapped = "ک";
    else if (mapped === "ۀ" || mapped === "ة") mapped = "ه";
    else if (mapped === "ؤ") mapped = "و";
    else if (mapped === "إ" || mapped === "أ" || mapped === "آ") mapped = "ا";
    if ((code >= 0x064b && code <= 0x065f) || code === 0x0670 || code === 0x200f ||
        (code >= 0x202a && code <= 0x202e)) return;
    if (code === 0x200c || /\\s/.test(original)) {
      if (text.length && pendingSpace == null) pendingSpace = index;
      return;
    }
    if (pendingSpace != null && text.length) {
      text += " ";
      offsets.push(pendingSpace);
    }
    pendingSpace = null;
    text += mapped;
    for (let i = 0; i < mapped.length; i++) offsets.push(index);
  });
  const trimmed = text.trimEnd();
  return { text: trimmed, offsets: offsets.slice(0, trimmed.length) };
}

function normalizeWebSearchText(source: string): string {
  return normalizeWebSearchWithOffsets(source).text;
}
