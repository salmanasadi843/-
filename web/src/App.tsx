import { FormEvent, useEffect, useMemo, useState } from "react";
import { onAuthStateChanged, signInWithEmailAndPassword, signOut, User } from "firebase/auth";
import { api, ClassRecord, Me, SessionDetail, SessionSummary, Teacher } from "./api";
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
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

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
  const visibleClasses = useMemo(() => classes.filter(c =>
    (selectedTeacher === "all" || c.ownerUserId === selectedTeacher) &&
    !c.isHidden && c.isPublished
  ), [classes, selectedTeacher]);

  async function login(event: FormEvent) {
    event.preventDefault(); if (!auth) return;
    setBusy(true); setError("");
    try { await signInWithEmailAndPassword(auth, email.trim(), password); setPassword(""); }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function openClass(item: ClassRecord) {
    if (!user) return;
    setSelectedClass(item); setDetail(null); setSessions([]); setBusy(true); setError("");
    try { setSessions(await api.sessions(item.id, await user.getIdToken())); }
    catch (e) { setError(message(e)); }
    finally { setBusy(false); }
  }
  async function openSession(item: SessionSummary) {
    if (!user || !selectedClass) return;
    setBusy(true); setError("");
    try { setDetail(await api.session(selectedClass.id, item.id, await user.getIdToken())); }
    catch (e) { setError(message(e)); }
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
    <div className="layout">
      <aside className="panel sidebar">
        <h2>استادان</h2>
        <button className={selectedTeacher === "all" ? "choice active" : "choice"} onClick={() => {setSelectedTeacher("all");setSelectedClass(null);setDetail(null);}}>همه استادان</button>
        {teachers.map(t => <button key={t.userId} className={selectedTeacher === t.userId ? "choice active" : "choice"} onClick={() => {setSelectedTeacher(t.userId);setSelectedClass(null);setDetail(null);}}>{t.displayName}</button>)}
      </aside>
      <section className="panel content">
        <div className="section-head"><h2>کلاس‌ها</h2><span className="muted">{visibleClasses.length} کلاس</span></div>
        <div className="class-grid">
          {visibleClasses.map(c => <button key={c.id} className={selectedClass?.id === c.id ? "class-card selected" : "class-card"} onClick={() => void openClass(c)}>
            <span className="tag">{c.visibility === "public" ? "عمومی" : "خصوصی"}</span><strong>{c.title}</strong><span className="muted">{teachers.find(t => t.userId === c.ownerUserId)?.displayName ?? "استاد"}</span>
          </button>)}
          {!visibleClasses.length && <p className="muted empty">کلاسی برای نمایش وجود ندارد.</p>}
        </div>
        {selectedClass && <div className="lower">
          <div className="section-head"><h2>جلسه‌های {selectedClass.title}</h2><span className="muted">{sessions.length} جلسه</span></div>
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
    <footer>نسخه آزمایشی سایت · مرور محتوا فقط خواندنی است · مشاهده عمومی بدون ورود هنوز فعال نشده است</footer>
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