import { FormEvent, useEffect, useMemo, useState } from "react";
import { onAuthStateChanged, signInWithEmailAndPassword, signOut, User } from "firebase/auth";
import { api, AccessCode, ClassRecord, Me, SessionDetail, SessionSummary, Teacher, TeacherFolder } from "./api";
import { auth, firebaseConfigured } from "./firebase";

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [me, setMe] = useState<Me | null>(null);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [folders, setFolders] = useState<TeacherFolder[]>([]);
  const [folderName, setFolderName] = useState("");
  const [selectedFolderId, setSelectedFolderId] = useState("");
  const [showFolderForm, setShowFolderForm] = useState(false);
  const [classes, setClasses] = useState<ClassRecord[]>([]);
  const [selectedTeacher, setSelectedTeacher] = useState("all");
  const [selectedClass, setSelectedClass] = useState<ClassRecord | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [showClassForm, setShowClassForm] = useState(false);
  const [showSessionForm, setShowSessionForm] = useState(false);
  const [editingClass, setEditingClass] = useState(false);
  const [editingSession, setEditingSession] = useState(false);
  const [deletedClasses, setDeletedClasses] = useState<ClassRecord[]>([]);
  const [showDeletedClasses, setShowDeletedClasses] = useState(false);
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
  const [newlyCreatedCode, setNewlyCreatedCode] = useState("");
  const [maxCodeUses, setMaxCodeUses] = useState("20");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    if (!auth) return;
    return onAuthStateChanged(auth, setUser);
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      if (!user) { setMe(null); setTeachers([]); setClasses([]); setFolders([]); return; }
      setBusy(true); setError("");
      try {
        const token = await user.getIdToken();
        const [profile, teacherList, classList, folderList] = await Promise.all([
          api.me(token), api.teachers(token), api.classes(token), api.teacherFolders(token),
        ]);
        if (!cancelled) { setMe(profile); setTeachers(teacherList); setClasses(classList); setFolders(folderList); }
      } catch (e) { if (!cancelled) setError(message(e)); }
      finally { if (!cancelled) setBusy(false); }
    }
    void load();
    return () => { cancelled = true; };
  }, [user]);

  const isAdmin = me?.roles.includes("super_admin") ?? false;
  const canCreateClass = isAdmin || (me?.roles.includes("teacher") ?? false);
  const canRedeemCode = me?.roles.includes("student") ?? false;
  const visibleClasses = useMemo(
    () => classes.filter(c => selectedTeacher === "all" || c.ownerUserId === selectedTeacher),
    [classes, selectedTeacher],
  );
  const folderOwnerId = isAdmin ? (selectedTeacher === "all" ? "" : selectedTeacher) : (me?.roles.includes("teacher") ? me.userId : "");
  const visibleFolders = useMemo(() => folders.filter(folder => folder.ownerUserId === folderOwnerId), [folders, folderOwnerId]);
  const canManageSelectedClass = !!selectedClass &&
    (isAdmin || selectedClass.ownerUserId === me?.userId) &&
    (isAdmin || (me?.roles.includes("teacher") ?? false));

  async function login(event: FormEvent) {
    event.preventDefault(); if (!auth) return;
    setBusy(true); setError("");
    try { await signInWithEmailAndPassword(auth, email.trim(), password); setPassword(""); }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function openClass(item: ClassRecord) {
    if (!user) return;
    setSelectedClass(item); setDetail(null); setSessions([]); setClassAccessCodes([]); setNewlyCreatedCode(""); setShowSessionForm(false); setBusy(true); setError("");
    try {
      const token = await user.getIdToken();
      const canManage = me?.roles.includes("super_admin") || (me?.roles.includes("teacher") && item.ownerUserId === me.userId);
      const [sessionList, codes] = await Promise.all([
        api.sessions(item.id, token),
        canManage ? api.accessCodes(item.id, token) : Promise.resolve([] as AccessCode[]),
      ]);
      setSessions(sessionList); setClassAccessCodes(codes);
    }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function openSession(item: SessionSummary) {
    if (!user || !selectedClass) return;
    setBusy(true); setError(""); setDetail(null);
    try { setDetail(await api.session(selectedClass.id, item.id, await user.getIdToken())); }
    catch (e) { setError(message(e)); }
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
      const created = await api.createTeacherFolder({ ...(isAdmin ? { ownerUserId: folderOwnerId } : {}), name: folderName.trim(), sortOrder: visibleFolders.length }, await user.getIdToken());
      setFolders(current => [...current, created]); setFolderName(""); setShowFolderForm(false); setNotice("پوشه استاد ایجاد شد.");
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
  async function editSelectedSession() {
    if (!detail || !canManageSelectedClass) return;
    setSessionTitle(detail.title); setSessionTranscript(detail.transcript);
    setSessionSummary(detail.aiSummary ?? ""); setSessionKeyPoints(detail.aiKeyPoints ?? "");
    setEditingSession(true); setShowSessionForm(true); setError("");
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
        title: sessionTitle.trim(), courseName: detail?.courseName ?? "", professorName: detail?.professorName ?? teacherName,
        dateMs: detail?.dateMs ?? now, transcript: sessionTranscript,
        originalTranscript: detail?.originalTranscript ?? sessionTranscript,
        aiSummary: sessionSummary.trim() || null, aiKeyPoints: sessionKeyPoints.trim() || null,
        isPublished: detail?.isPublished ?? true, lastEditedMs: now, sortOrder: detail?.sortOrder ?? sessions.length,
        courseId: detail?.courseId ?? null, audioDurationMs: detail?.audioDurationMs ?? 0,
        tags: detail?.tags ?? "", aiQuizJson: detail?.aiQuizJson ?? null,
      };
      if (editingSession && detail) await api.updateSession(detail.id, payload, await user.getIdToken());
      else await api.createSession(selectedClass.id, payload, await user.getIdToken());
      setSessionTitle(""); setSessionTranscript(""); setSessionSummary(""); setSessionKeyPoints("");
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

  if (!user) return <main className="center"><form className="panel narrow" onSubmit={login}>
    <Brand /><h1>ورود به درس‌یار</h1><p className="muted">با حساب کاربری فعلی درس‌یار وارد شوید.</p>
    <label>ایمیل<input type="email" autoComplete="username" value={email} onChange={e => setEmail(e.target.value)} required /></label>
    <label>گذرواژه<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required /></label>
    {error && <p className="error">{error}</p>}
    <button disabled={busy}>{busy ? "در حال ورود…" : "ورود"}</button>
  </form></main>;

  return <main className="shell">
    <header className="topbar"><Brand /><div className="account"><span>{user.email}</span><button className="ghost" onClick={() => void signOut(auth!)}>خروج</button></div></header>
    <section className="welcome"><div><p className="eyebrow">محیط آموزشی درس‌یار</p><h1>{isAdmin ? "مدیریت و مرور محتوا" : "کلاس‌های آموزشی"}</h1><p>ساختار استاد ← کلاس ← جلسه</p></div><span className="role">{roleLabel(me?.roles ?? [])}</span></section>
    {error && <p className="error notice">{error}</p>}
    {notice && <p className="success notice">{notice}</p>}
    <div className="layout">
      <aside className="panel sidebar">
        {canRedeemCode && <form className="join-form" onSubmit={redeemCode}>
          <h2>عضویت در کلاس خصوصی</h2>
          <label>کد ورود کلاس<input value={accessCode} onChange={e => setAccessCode(e.target.value)} placeholder="کد ورود را وارد کنید" required /></label>
          <button disabled={busy || !accessCode.trim()}>{busy ? "در حال بررسی…" : "ورود به کلاس"}</button>
        </form>}
        <h2>استادان</h2>
        <button className={selectedTeacher === "all" ? "choice active" : "choice"} onClick={() => {setSelectedTeacher("all");setSelectedClass(null);setDetail(null);}}>همه استادان</button>
        {teachers.map(t => <button key={t.userId} className={selectedTeacher === t.userId ? "choice active" : "choice"} onClick={() => {setSelectedTeacher(t.userId);setSelectedClass(null);setDetail(null);}}>{t.displayName}</button>)}
        {(folderOwnerId || (!isAdmin && me?.roles.includes("teacher"))) && <section className="folder-manager">
          <div className="section-head"><h2>پوشه‌های استاد</h2><button className="ghost" onClick={() => setShowFolderForm(v => !v)}>{showFolderForm ? "بستن" : "＋ پوشه"}</button></div>
          {showFolderForm && <form onSubmit={createTeacherFolder} className="folder-form"><input value={folderName} onChange={e => setFolderName(e.target.value)} placeholder="نام پوشه جدید" required /><button disabled={busy || !folderName.trim()}>ایجاد</button></form>}
          {visibleFolders.map(folder => <div className="folder-row" key={folder.id}>
            <span>{folder.name}{folder.isHidden ? " · مخفی" : ""}</span>
            <span><button className="ghost" disabled={busy} onClick={() => void renameTeacherFolder(folder)}>تغییر نام</button><button className="ghost" disabled={busy} onClick={() => void toggleFolderHidden(folder)}>{folder.isHidden ? "نمایش" : "مخفی"}</button><button className="ghost danger-text" disabled={busy} onClick={() => void removeTeacherFolder(folder)}>حذف</button></span>
          </div>)}
          {!visibleFolders.length && <p className="muted">پوشه‌ای برای این استاد ثبت نشده است.</p>}
        </section>}
        {isAdmin && selectedTeacher === "all" && <p className="muted">برای مدیریت پوشه‌ها، یک استاد را انتخاب کنید.</p>}
      </aside>
      <section className="panel content">
        <div className="section-head"><h2>کلاس‌ها</h2><span className="muted">{visibleClasses.length} کلاس</span></div>
        {canCreateClass && <div className="toolbar"><button className="action" onClick={() => {setEditingClass(false);setShowClassForm(v => !v);setError("");}}>{showClassForm ? "بستن فرم" : "＋ ایجاد کلاس"}</button>{isAdmin && <button className="ghost" onClick={() => void (showDeletedClasses ? setShowDeletedClasses(false) : loadDeletedClasses())}>{showDeletedClasses ? "بستن بازیابی" : "بازیابی کلاس‌های حذف‌شده"}</button>}{isAdmin && selectedTeacher === "all" && <span className="muted">برای ساخت کلاس، ابتدا استاد موردنظر را انتخاب کنید.</span>}</div>}
        {showDeletedClasses && isAdmin && <section className="access-manager"><div className="section-head"><h3>کلاس‌های حذف‌شده</h3><span className="muted">{deletedClasses.length} کلاس</span></div>{deletedClasses.map(c => <div className="code-row" key={c.id}><span><strong>{c.title}</strong><small>{teachers.find(t => t.userId === c.ownerUserId)?.displayName ?? "استاد"}</small></span><button className="ghost" disabled={busy} onClick={() => void restoreDeletedClass(c.id)}>بازیابی</button></div>)}{!deletedClasses.length && <p className="muted">کلاس حذف‌شده‌ای وجود ندارد.</p>}</section>}
        {showClassForm && <form className="editor-form" onSubmit={createClass}>
          <h3>{editingClass ? "ویرایش کلاس" : "کلاس جدید"}</h3>
          <label>عنوان کلاس<input value={classTitle} onChange={e => setClassTitle(e.target.value)} required /></label>
          <label>توضیحات<textarea value={classDescription} onChange={e => setClassDescription(e.target.value)} rows={2} /></label>
          <label>ترم یا سال تحصیلی<input value={classTerm} onChange={e => setClassTerm(e.target.value)} /></label>
          <label>دسترسی<select value={classVisibility} onChange={e => setClassVisibility(e.target.value as "public" | "private")}><option value="public">عمومی ـ بدون نیاز به عضویت</option><option value="private">خصوصی ـ نیازمند عضویت</option></select></label>
          <label>پوشه استاد<select value={selectedFolderId} onChange={e => setSelectedFolderId(e.target.value)}><option value="">بدون پوشه</option>{visibleFolders.filter(folder => !folder.isHidden || folder.id === selectedFolderId).map(folder => <option key={folder.id} value={folder.id}>{folder.name}{folder.isHidden ? " (مخفی)" : ""}</option>)}</select></label>
          <button disabled={busy || !classTitle.trim() || (isAdmin && selectedTeacher === "all")}>{busy ? "در حال ذخیره…" : editingClass ? "ذخیره تغییرات" : "ذخیره کلاس"}</button>
        </form>}
        <div className="class-grid">
          {visibleClasses.map(c => <button key={c.id} className={selectedClass?.id === c.id ? "class-card selected" : "class-card"} onClick={() => void openClass(c)}>
            <span className="tag">{c.visibility === "public" ? "عمومی" : "خصوصی"}{!c.isPublished ? " · پیش‌نویس" : ""}</span><strong>{c.title}</strong><span className="muted">{teachers.find(t => t.userId === c.ownerUserId)?.displayName ?? "استاد"}</span>
          </button>)}
          {!visibleClasses.length && <p className="muted empty">کلاسی برای نمایش وجود ندارد.</p>}
        </div>
        {selectedClass && <div className="lower">
          <div className="section-head"><h2>جلسه‌های {selectedClass.title}</h2><span className="muted">{sessions.length} جلسه</span></div>
          {canManageSelectedClass && <div className="toolbar"><button className="ghost" onClick={() => void editSelectedClass()}>ویرایش کلاس</button><button className="ghost danger-text" disabled={busy} onClick={() => void removeSelectedClass()}>حذف کلاس</button></div>}
          {canManageSelectedClass && <div className="toolbar"><button className="action" onClick={() => {setEditingSession(false);setShowSessionForm(v => !v);}}>{showSessionForm ? "بستن فرم" : "＋ افزودن جلسه"}</button></div>}
          {showSessionForm && canManageSelectedClass && <form className="editor-form" onSubmit={createSession}>
            <h3>{editingSession ? "ویرایش جلسه" : "جلسه جدید"}</h3>
            <label>عنوان جلسه<input value={sessionTitle} onChange={e => setSessionTitle(e.target.value)} required /></label>
            <label>متن جلسه<textarea value={sessionTranscript} onChange={e => setSessionTranscript(e.target.value)} rows={5} /></label>
            <label>خلاصه هوشمند (اختیاری)<textarea value={sessionSummary} onChange={e => setSessionSummary(e.target.value)} rows={3} /></label>
            <label>نکات کلیدی (اختیاری)<textarea value={sessionKeyPoints} onChange={e => setSessionKeyPoints(e.target.value)} rows={3} /></label>
            <button disabled={busy || !sessionTitle.trim()}>{busy ? "در حال ذخیره…" : editingSession ? "ذخیره تغییرات" : "ذخیره جلسه"}</button>
          </form>}
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
          {sessions.map(s => <button key={s.id} className="session-row" onClick={() => void openSession(s)}><span>{s.title}</span><span className="muted">{s.courseName}</span></button>)}
          {!sessions.length && !busy && <p className="muted">جلسه‌ای ثبت نشده است.</p>}
        </div>}
        {detail && <article className="detail">
          <div className="toolbar"><h2>{detail.title}</h2>{canManageSelectedClass && <><button className="ghost" onClick={() => void editSelectedSession()}>ویرایش جلسه</button><button className="ghost danger-text" disabled={busy} onClick={() => void removeSelectedSession()}>حذف جلسه</button></>}</div>
          {detail.aiSummary && <><h3>خلاصه هوشمند</h3><p className="pre">{detail.aiSummary}</p></>}
          {detail.aiKeyPoints && <><h3>نکات کلیدی</h3><p className="pre">{detail.aiKeyPoints}</p></>}
          {detail.originalTranscript && <><h3>متن اصلی</h3><p className="pre">{detail.originalTranscript}</p></>}
          {detail.transcript && detail.transcript !== detail.originalTranscript && <><h3>متن ویرایش‌شده</h3><p className="pre">{detail.transcript}</p></>}
        </article>}
      </section>
    </div>
    <footer>نسخه آزمایشی سایت · ایجاد کلاس و جلسه فعال است · ویرایش و حذف محتوا در مرحله بعد اضافه می‌شود</footer>
  </main>;
}
function Brand() { return <div className="brand"><span className="brand-mark">د</span><span>درس‌یار</span></div>; }
function message(e: unknown) { return e instanceof Error ? e.message : "خطای پیش‌بینی‌نشده رخ داد."; }
function roleLabel(roles: string[]) {
  if (roles.includes("super_admin")) return "مدیر اصلی";
  if (roles.includes("teacher")) return "استاد";
  if (roles.includes("student")) return "دانشجو";
  return "کاربر";
}