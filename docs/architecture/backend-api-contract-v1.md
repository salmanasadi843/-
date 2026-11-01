# قرارداد اولیه Backend API درس‌یار — نسخه 1

وضعیت: قرارداد هدف نسخه 1 برای staging. بخشی از endpointهای دامنه‌ای در Backend آزمایشی پیاده‌سازی و با PostgreSQL موقت آزموده شده‌اند؛ این سند در بخش «وضعیت پیاده‌سازی فعلی» تفاوت قرارداد هدف و مسیرهای موجود را مشخص می‌کند. هیچ کلاینتی هنوز به Backend آزمایشی متصل نیست و این API در محیط عملیاتی مستقر نشده است.

## وضعیت پیاده‌سازی فعلی (بازبینی کد در 2026-11-01)

مسیرهای زیر در `backend/src/main/kotlin/ir/darsyar/backend/Application.kt` ثبت شده‌اند و پشت احراز هویت Firebase قرار دارند، مگر health/readiness و معرفی API:

- `GET /health/live`, `GET /health/ready`, `GET /api/v1`, `GET /api/v1/me`.
- درخواست استادی: `POST /api/v1/teacher-applications`; مدیریت مدیر: `GET /api/v1/admin/teacher-applications` و `PATCH /api/v1/admin/teacher-applications/{userId}`.
- پوشه استاد: `GET/POST /api/v1/teacher-folders`, `PATCH/DELETE /api/v1/teacher-folders/{folderId}`.
- کلاس‌ها: `GET/POST /api/v1/classes`, `GET /api/v1/classes/deleted`, `PATCH/DELETE /api/v1/classes/{classId}`, `POST /api/v1/classes/{classId}/restore`.
- دوره‌ها: `GET/POST /api/v1/classes/{classId}/courses`, `PATCH/DELETE /api/v1/courses/{courseId}`.
- جلسات: `GET/POST /api/v1/classes/{classId}/sessions`, `GET /api/v1/classes/{classId}/sessions/{sessionId}`, `PATCH/DELETE /api/v1/sessions/{sessionId}`, `POST /api/v1/sessions/{sessionId}/restore`.
- فهرست جلسه‌ها فقط metadata خلاصه را برمی‌گرداند؛ transcript، متن اصلی، خلاصه هوشمند و نکات کلیدی فقط از endpoint جزئیات همان جلسه انتخاب‌شده دریافت می‌شود. جزئیات با همان قواعد visibility سمت سرور و query محدود به classId و sessionId کنترل می‌شود.
- منابع صوتی: `GET/POST /api/v1/sessions/{sessionId}/audio-sources`, `PATCH/DELETE /api/v1/sessions/{sessionId}/audio-sources/{sourceId}`.
- کد ورود: `GET/POST /api/v1/classes/{classId}/access-codes`, `DELETE /api/v1/access-codes/{codeId}`, `POST /api/v1/class-memberships/redeem`.
- عضویت: `GET /api/v1/classes/{classId}/memberships`, `DELETE /api/v1/classes/{classId}/memberships/{studentUserId}`.

نکته مهم دسترسی عمومی: مسیرهای فعلی زیر `authenticate("firebase")` هستند؛ بنابراین کلاس `public` در Backend فعلی یعنی بدون عضویت کلاس برای حساب واردشده قابل مشاهده است، نه مشاهده ناشناس بدون ورود. همچنین مسیر مستقل `GET /api/v1/classes/{classId}` برای جزئیات یک کلاس و صفحه‌بندی فهرست هنوز پیاده نشده‌اند. تا زمان افزودن و آزمون endpoint عمومی، ستون «مهمان» در ماتریس بالا قابلیت هدف آینده است و نباید در سایت به‌عنوان قابلیت موجود نمایش داده شود.

این فهرست، مسیرهای ثبت‌شده در کد است و به‌معنی تکمیل همه نیازهای محصول یا اتصال کلاینت نیست. مواردی مانند `POST /api/v1/auth/exchange`، فهرست ثبت‌نام‌های کاربر، endpointهای sync، صفحه‌بندی cursor-based، وضعیت شخصی جلسه و OpenAPI کامل هنوز در این فاز به‌عنوان قرارداد هدف/کار باقیمانده‌اند. نام‌های قرارداد اولیه مانند `teacher-requests` و `classes/{classId}/join` با مسیر واقعی فعلی یکسان نیستند؛ مسیرهای بالا مرجع پیاده‌سازی فعلی‌اند.

آزمون‌های CI با PostgreSQL موقت، مجوزهای مسیرهای کلاس، پوشه، دوره، جلسه، صوت، کد ورود و عضویت را بررسی می‌کنند. موفقیت CI staging به‌تنهایی پذیرش امنیتی یا مجوز استقرار عملیاتی نیست.

## 1. اصول قرارداد

- Android و Web فقط از HTTPS API استفاده می‌کنند؛ دسترسی مستقیم کلاینت به PostgreSQL ممنوع است.
- مسیرها با `/api/v1` نسخه‌بندی می‌شوند.
- شناسه‌های مقصد UUID هستند. شناسه‌های عددی Room فقط در `legacy_id_map` باقی می‌مانند.
- همه زمان‌ها در API به ISO-8601 UTC ارسال می‌شوند. تبدیل به تاریخ شمسی فقط در UI انجام می‌شود؛ مقدار epoch قدیمی در import حفظ و قابل تطبیق می‌ماند.
- صفحه‌بندی فهرست‌ها cursor-based است؛ endpoint نباید همه کلاس‌ها/جلسات را یکجا برگرداند.
- عملیات نوشتن باید idempotency و کنترل نسخه داشته باشند.
- حذف منطقی (tombstone) تا پایان پنجره همگام‌سازی نگهداری می‌شود؛ پاک‌سازی فیزیکی فقط با سیاست نگهداری و backup.

## 2. احراز هویت در دوره گذار

1. Android/Web در مرحله اول Firebase Auth فعلی را حفظ می‌کنند.
2. کلاینت Firebase ID Token را در `Authorization: Bearer <token>` می‌فرستد.
3. آداپتور احراز هویت Backend توکن را با Firebase Admin SDK و پروژه مورد انتظار اعتبارسنجی می‌کند.
4. Backend از `auth_identities(provider='firebase', provider_subject=uid)` کاربر داخلی را پیدا می‌کند؛ در صورت نبود نگاشت، فقط مسیر onboarding مجاز می‌تواند آن را ایجاد کند.
5. نقش‌ها و تأیید استاد از جداول Backend خوانده می‌شوند، نه از body درخواست، SharedPreferences یا ادعای کلاینت.
6. مدیر اصلی فقط از فرایند bootstrap امن و ثبت‌شده در محیط سرور تعیین می‌شود؛ ایمیل یا UID ارسالی کاربر، مجوز مدیر ایجاد نمی‌کند.
7. در آینده می‌توان آداپتور Firebase را با ارائه‌دهنده هویت دیگری جایگزین کرد، بدون تغییر شناسه داخلی کاربر و داده‌های آموزشی.

کلید service account فقط در secrets سرور است؛ هرگز در APK، وب‌سایت عمومی، Git یا Docker image قرار نمی‌گیرد.

## 3. قرارداد پاسخ و خطا

موفقیت، JSON با ساختار endpoint-specific برمی‌گرداند. خطاها از ساختار ثابت زیر استفاده می‌کنند:

```json
{
  "error": {
    "code": "FORBIDDEN",
    "message": "You do not have permission to perform this action.",
    "requestId": "server-generated-id",
    "details": {}
  }
}
```

کدهای مهم:
- `400 VALIDATION_ERROR`
- `401 UNAUTHENTICATED`
- `403 FORBIDDEN`
- `404 NOT_FOUND` (بدون افشای وجود محتوای خصوصی)
- `409 VERSION_CONFLICT`
- `409 IDEMPOTENCY_KEY_REUSED`
- `429 RATE_LIMITED`
- `500 INTERNAL_ERROR`

پیام قابل نمایش فارسی در کلاینت نگاشت می‌شود؛ جزئیات داخلی، SQL، token و stack trace به کاربر برگردانده نمی‌شود.

## 4. Endpointهای اولیه

### هویت و پروفایل

- `POST /api/v1/auth/exchange` — اعتبارسنجی توکن گذار و دریافت شناسه داخلی/پروفایل.
- `GET /api/v1/me` — کاربر، نقش‌ها، وضعیت و مجوزهای خلاصه.
- `GET /api/v1/me/enrollments` — کلاس‌های خصوصی/عمومی مجاز کاربر.
- `POST /api/v1/teacher-requests` — درخواست تأیید استادی.
- `GET /api/v1/admin/teacher-requests` — فهرست درخواست‌ها، فقط مدیر.
- `POST /api/v1/admin/teacher-requests/{requestId}/approve`
- `POST /api/v1/admin/teacher-requests/{requestId}/reject`

### پوشه، کلاس، درس و جلسه

- `GET /api/v1/teachers/{teacherId}/folders`
- `POST /api/v1/teacher-folders`
- `PATCH /api/v1/teacher-folders/{folderId}`
- `DELETE /api/v1/teacher-folders/{folderId}`
- `GET /api/v1/classes` (فهرست قابل‌مشاهده برای حساب احراز هویت‌شده؛ cursor/limit در نسخه فعلی پیاده نشده است)
- `POST /api/v1/classes`
- `PATCH /api/v1/classes/{classId}`
- `DELETE /api/v1/classes/{classId}`
- `GET /api/v1/classes/{classId}/courses`
- `POST /api/v1/classes/{classId}/courses`
- `PATCH /api/v1/courses/{courseId}`
- `DELETE /api/v1/courses/{courseId}`
- `GET /api/v1/classes/{classId}/sessions` — فهرست خلاصه بدون متن جلسه.
- `POST /api/v1/classes/{classId}/sessions`
- `GET /api/v1/classes/{classId}/sessions/{sessionId}` — جزئیات کامل فقط برای جلسه انتخاب‌شده و پس از کنترل مجوز.
- `PATCH /api/v1/sessions/{sessionId}`
- `DELETE /api/v1/sessions/{sessionId}`

### انتشار و کلاس خصوصی

- `POST /api/v1/classes/{classId}/publish`
- `POST /api/v1/classes/{classId}/unpublish`
- `POST /api/v1/classes/{classId}/access-codes`
- `POST /api/v1/classes/{classId}/join` — کد ورود یا دعوت؛ کد خام در URL یا log ثبت نمی‌شود.
- `DELETE /api/v1/classes/{classId}/members/{userId}`

### صوت و وضعیت شخصی

- `GET/POST /api/v1/sessions/{sessionId}/audio-sources`
- `PATCH/DELETE /api/v1/audio-sources/{sourceId}`
- `PUT /api/v1/me/sessions/{sessionId}/state` — علاقه‌مندی و موقعیت پخش شخصی.

### مدیر اصلی

- `GET /api/v1/admin/teachers`
- `GET /api/v1/admin/teachers/{teacherId}/content`
- عملیات مدیریت محتوا از endpointهای عمومی با مجوز super_admin یا مسیرهای اختصاصی admin؛ انتخاب نهایی باید در OpenAPI مشخص شود.
- همه عملیات cross-owner در audit log ثبت می‌شوند.

## 5. ماتریس مجوز

| عملیات | مدیر اصلی | مالک استاد | استاد دیگر | دانشجو | مهمان |
|---|---|---|---|---|---|
| مشاهده کلاس عمومی منتشرشده | بله | بله | بله | بله | در نسخه فعلی خیر؛ نیازمند endpoint عمومی جداگانه |
| مشاهده کلاس خصوصی | بله | بله | خیر، مگر مجوز جداگانه | عضو فعال | خیر |
| ویرایش/حذف محتوای هر استاد | بله | محتوای خود | خیر | خیر | خیر |
| ویرایش پوشه استاد دیگر | بله | پوشه خود | خیر | خیر | خیر |
| مشاهده محتوای منتشرشده استاد دیگر | بله | بله | خواندنی | طبق دسترسی | فقط عمومی |
| مدیریت عضویت کلاس | بله | کلاس خود | خیر | خیر | خیر |

مجوز در هر درخواست سمت سرور بررسی می‌شود؛ مخفی‌کردن دکمه در UI کنترل امنیتی محسوب نمی‌شود.

## 6. کنترل نسخه و تکرارپذیری

- هر منبع قابل ویرایش دارای `version` افزایشی است.
- کلاینت در PATCH مقدار نسخه مورد انتظار را می‌فرستد؛ اگر نسخه سرور تغییر کرده باشد، پاسخ `409 VERSION_CONFLICT` است.
- هر عملیات نوشتن/تکرارشونده `Idempotency-Key` یکتا دارد.
- درخواست تکراری با همان کلید و همان body پاسخ قبلی را می‌گیرد؛ همان کلید با body متفاوت رد می‌شود.
- حذف به‌صورت tombstone و با نسخه جدید ثبت می‌شود تا دستگاه آفلاین رکورد حذف‌شده را دوباره زنده نکند.

## 7. همگام‌سازی Android و Room

فاز اتصال Android باید Room را cache محلی حفظ کند:

- `GET /api/v1/sync/changes?cursor=...` برای دریافت تغییرات از آخرین cursor.
- `POST /api/v1/sync/commands` برای ارسال فرمان‌های محلی صف‌شده، هر فرمان با شناسه یکتا، نوع عملیات، شناسه مقصد، نسخه پایه و payload.
- سرور نتیجه هر فرمان را مستقل برمی‌گرداند: پذیرفته، تعارض، ممنوع یا نیازمند حل مالکیت.
- صف محلی فقط پس از تأیید سرور علامت‌گذاری می‌شود.
- تعارض متن اصلی و متن ویرایش‌شده نباید با last-write-wins بی‌قید حل شود؛ نسخه‌ها نگهداری و برای تصمیم کاربر/سیاست مشخص عرضه می‌شوند.
- در فاز اول می‌توان نوشتن آنلاین را فعال و صف آفلاین را محدود نگه داشت؛ اما حذف cache آفلاین از دامنه طراحی نیست.

## 8. جست‌وجو و حریم خصوصی

- جست‌وجوی عمومی فقط کلاس/جلسه عمومی منتشرشده را برمی‌گرداند.
- جست‌وجوی داخل حساب فقط رکوردهایی را برمی‌گرداند که کاربر مجاز به دیدن آن‌هاست.
- transcript، originalTranscript، یادداشت‌ها و متن‌های خصوصی در index عمومی قرار نمی‌گیرند.
- pagination و rate limit برای endpointهای جست‌وجو الزامی است.

## 9. خارج از دامنه نسخه اول

- پرداخت و اشتراک
- میزبانی/پراکسی فایل صوتی
- انتقال خودکار فایل‌های محلی دستگاه
- dual-write هم‌زمان Firebase و PostgreSQL
- مهاجرت رمز عبور Firebase به‌صورت فرضی یا استخراج رمز خام
- انتشار عمومی محتوایی که مالکیت یا مجوز آن unresolved است

## 10. گام بعد از تأیید طراحی

1. تکمیل inventory داده Firestore و تعیین تکلیف unresolved ownership.
2. ساخت پروژه Ktor مستقل با health/readiness، OpenAPI و Docker.
3. افزودن Flyway و اجرای schema فقط روی PostgreSQL staging خالی.
4. پیاده‌سازی auth adapter گذار و تست نقش‌ها.
5. ساخت import/export آزمایشی idempotent با گزارش شمارش و checksum.
6. اجرای restore روی میزبان دوم/محیط جداگانه و آزمون قطع میزبان اصلی.
7. پس از موفقیت، نمونه وب و اتصال آزمایشی Android.

هیچ مرحله‌ای از این فهرست مجوز تغییر تولیدی یا انتقال واقعی داده نیست.
