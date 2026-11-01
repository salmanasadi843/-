import { FormEvent, useEffect, useMemo, useState } from "react";
import { onAuthStateChanged, signInWithEmailAndPassword, signOut, User } from "firebase/auth";
import { api, AccessCode, ClassRecord, Me, SessionDetail, SessionSummary, Teacher } from "./api";
import { auth, firebaseConfigured } from "./firebase";

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [me, setMe] = useState<Me | null>(null);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [classes, setClasses] = useState<ClassRecord[]>([]);
  const [selectedTeacher, setSelectedTeacher] = useState("all");
  const [selectedClass, setSelectedClass] = useState<ClassRecord | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [showClassForm, setShowClassForm] = useState(false);
  const [showSessionForm, setShowSessionForm] = useState(false);
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
      if (!user) { setMe(null); setTeachers([]); setClasses([]); return; }
      setBusy(true); setError("");
      try {
        const token = await user.getIdToken();
        const [profile, teacherList, classList] = await Promise.all([
          api.me(token), api.teachers(token), api.classes(token),
        ]);
        if (!cancelled) { setMe(profile); setTeachers(teacherList); setClasses(classList); }
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
        visibility: classVisibility, isPublished: true, isHidden: false, sortOrder: 0,
      };
      const created = await api.createClass(data, await user.getIdToken());
      setClasses(current => [...current, created]);
      setClassTitle(""); setClassDescription(""); setClassTerm(""); setClassVisibility("private");
      setShowClassForm(false); setNotice("کلاس با موفقیت ایجاد شد.");
      setSelectedTeacher(created.ownerUserId);
      await openClass(created);
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
      await api.createSession(selectedClass.id, {
        title: sessionTitle.trim(), courseName: "", professorName: teacherName, dateMs: now,
        transcript: sessionTranscript, originalTranscript: sessionTranscript,
        aiSummary: sessionSummary.trim() || null, aiKeyPoints: sessionKeyPoints.trim() || null,
        isPublished: true, lastEditedMs: now, sortOrder: sessions.length,
      }, await user.getIdToken());
      setSessionTitle(""); setSessionTranscript(""); setSessionSummary(""); setSessionKeyPoints("");
      setShowSessionForm(false); setNotice("جلسه با موفقیت ایجاد شد.");
      setSessions(await api.sessions(selectedClass.id, await user.getIdToken()));
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
      </aside>
      <section className="panel content">
        <div className="section-head"><h2>کلاس‌ها</h2><span className="muted">{visibleClasses.length} کلاس</span></div>
        {canCreateClass && <div className="toolbar"><button className="action" onClick={() => {setShowClassForm(v => !v);setError("");}}>{showClassForm ? "بستن فرم" : "＋ ایجاد کلاس"}</button>{isAdmin && selectedTeacher === "all" && <span className="muted">برای ساخت کلاس، ابتدا استاد موردنظر را انتخاب کنید.</span>}</div>}
        {showClassForm && <form className="editor-form" onSubmit={createClass}>
          <h3>کلاس جدید</h3>
          <label>عنوان کلاس<input value={classTitle} onChange={e => setClassTitle(e.target.value)} required /></label>
          <label>توضیحات<textarea value={classDescription} onChange={e => setClassDescription(e.target.value)} rows={2} /></label>
          <label>ترم یا سال تحصیلی<input value={classTerm} onChange={e => setClassTerm(e.target.value)} /></label>
          <label>دسترسی<select value={classVisibility} onChange={e => setClassVisibility(e.target.value as "public" | "private")}><option value="public">عمومی ـ بدون نیاز به عضویت</option><option value="private">خصوصی ـ نیازمند عضویت</option></select></label>
          <button disabled={busy || !classTitle.trim() || (isAdmin && selectedTeacher === "all")}>{busy ? "در حال ذخیره…" : "ذخیره کلاس"}</button>
        </form>}
        <div className="class-grid">
          {visibleClasses.map(c => <button key={c.id} className={selectedClass?.id === c.id ? "class-card selected" : "class-card"} onClick={() => void openClass(c)}>
            <span className="tag">{c.visibility === "public" ? "عمومی" : "خصوصی"}{!c.isPublished ? " · پیش‌نویس" : ""}</span><strong>{c.title}</strong><span className="muted">{teachers.find(t => t.userId === c.ownerUserId)?.displayName ?? "استاد"}</span>
          </button>)}
          {!visibleClasses.length && <p className="muted empty">کلاسی برای نمایش وجود ندارد.</p>}
        </div>
        {selectedClass && <div className="lower">
          <div className="section-head"><h2>جلسه‌های {selectedClass.title}</h2><span className="muted">{sessions.length} جلسه</span></div>
          {canManageSelectedClass && <div className="toolbar"><button className="action" onClick={() => setShowSessionForm(v => !v)}>{showSessionForm ? "بستن فرم" : "＋ افزودن جلسه"}</button></div>}
          {showSessionForm && canManageSelectedClass && <form className="editor-form" onSubmit={createSession}>
            <h3>جلسه جدید</h3>
            <label>عنوان جلسه<input value={sessionTitle} onChange={e => setSessionTitle(e.target.value)} required /></label>
            <label>متن جلسه<textarea value={sessionTranscript} onChange={e => setSessionTranscript(e.target.value)} rows={5} /></label>
            <label>خلاصه هوشمند (اختیاری)<textarea value={sessionSummary} onChange={e => setSessionSummary(e.target.value)} rows={3} /></label>
            <label>نکات کلیدی (اختیاری)<textarea value={sessionKeyPoints} onChange={e => setSessionKeyPoints(e.target.value)} rows={3} /></label>
            <button disabled={busy || !sessionTitle.trim()}>{busy ? "در حال ذخیره…" : "ذخیره جلسه"}</button>
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
          <h2>{detail.title}</h2>
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