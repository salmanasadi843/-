# Audit status

The Room model is version 8 and contains ClassEntity, CourseEntity, LectureEntity, and TeacherFolderEntity. ClassEntity has ownerUid and folderId. CourseEntity and LectureEntity derive ownership through class relationships. LectureEntity already has audioUrl. Firestore stores private teacher backups under teacher_data/{uid}, and published material snapshots under shared_materials/{uid}. Firebase UID must be preserved as a legacy identity mapping. The current rules do not explicitly grant the primary administrator read access to other teachers private teacher_data. Room fallbackToDestructiveMigration(true) is enabled, so schema changes require special care. No production data has been moved or changed.

## Read-only Android/Firebase code audit — 2026-11-01

This section records code-level findings only. It does not inspect live Firebase documents,
Room databases on user devices, production accounts, or the Iranian server. No live data
was queried, changed, exported, linked, or migrated.

### Confirmed data model and ownership signals

- Room schema version is 8. Local primary keys are numeric and device-database scoped.
- `classes.ownerUid` is the only direct Firebase ownership field in the Room teaching
  hierarchy. Courses and lectures inherit ownership through `classId`; folders carry
  `ownerUid` directly.
- A blank `ClassEntity.ownerUid` is explicitly allowed for legacy rows. Such rows cannot
  safely be attributed to the currently signed-in teacher without independent evidence.
- Shared-feed records use deterministic negative IDs and are excluded from private
  teacher publishing by the shared-materials publishing path.
- The shared-feed importer deduplicates classes using normalized metadata and published
  content fingerprints, even across different feed-owner UIDs. This is useful for display
  deduplication but is not a safe identity/ownership rule for migration. The original
  owner must remain a first-class part of the imported identity; similar or identical
  class content must not authorize merging ownership.

### Confirmed backup and restore risks

1. `DatabaseBackup` exports the lecture's edited transcript but omits
   `originalTranscript` and lecture-level `isPublished`. Import therefore cannot
   round-trip those fields. The audio binary path is intentionally excluded, while
   `audioUrl` is retained.
2. `FirestoreSyncRepository.uploadSnapshot` includes positive-ID classes when
   `ownerUid == uid OR ownerUid is blank`. This compatibility rule can place ownerless
   legacy classes into the current teacher's private cloud backup. It must not be used
   as evidence of ownership in any future migration.
3. The same private backup filters two lecture titles by hard-coded title strings.
   This is a content-specific exclusion and must not be carried into a general-purpose
   export or migration.
4. Private cloud restore validates record shapes before replacement, but then replaces
   local class/course/lecture collections. It must remain a user-initiated recovery
   operation, not be repurposed as an incremental cross-account migration.
5. Room currently enables `fallbackToDestructiveMigration(true)`. A missing migration
   can destroy local data; do not change Room entities/schema or add a migration as part
   of the backend portability work until a verified, tested backup/recovery path exists.

### Identity and role boundary

- Android's current sign-in reads a Firestore `users/{uid}` profile and derives the
  legacy UI role from that profile, with a special primary-teacher email rule.
- The new backend deliberately uses a different trust boundary: Firebase verifies the
  token, but application roles are loaded from PostgreSQL. The Android app is not yet
  connected to this backend, so these two role systems must not be treated as already
  reconciled.
- The only safe initial mapping key is the independently verified Firebase UID. Email,
  display name, class title, and content similarity are not sufficient to merge accounts
  or assign ownership.

### Required migration inventory before any data movement

Build an offline, read-only inventory with counts and exception buckets, without exporting
transcripts or other sensitive content:

- Firebase account UID and whether a corresponding profile exists; report only aggregate
  counts and non-reversible internal review references, not emails or names.
- Room class counts grouped by `ownerUid`: exact known UID, blank UID, and malformed or
  otherwise unmatched UID.
- Course and lecture counts grouped through their parent class owner; separately count
  missing/invalid parent relationships.
- Folder counts grouped by owner UID and check folder references against their owner.
- Duplicate local IDs only within their source database; do not compare numeric IDs across
  devices as if they were globally unique.
- Shared-feed records and private teacher-backup records must be inventoried separately.
- Produce an explicit unresolved/quarantine bucket. No fuzzy matching, automatic owner
  assignment, cross-teacher deduplication, or role grants are allowed.

### Current decision

The code audit is sufficient to define the safe inventory rules, but it does not establish
which real accounts own any particular class or lecture. The next safe deliverable is a
read-only inventory/export specification and validator using synthetic fixtures first.
Actual account-to-content reconciliation remains a separately approved operator task.

## سیاست دسترسی مهمان و امکانات تعاملی — 2026-11-02

- سه endpoint عمومی کلاس/جلسه در Backend فقط محتوای عمومی، منتشرشده و غیرمخفی را ارائه می‌کنند؛ هیچ endpoint عمومی برای پرسش هوش مصنوعی تعریف نشده است.
- در صفحه عمومی وب، انتخاب متن و هایلایت موقت در همان نشست اضافه شده است. این هایلایت در حساب یا سرور ذخیره نمی‌شود و با خروج از صفحه از بین می‌رود.
- متن عمومی، خلاصه و نکات کلیدی برای مهمان قابل مشاهده است. پرسش از هوش مصنوعی، ذخیره هایلایت، یادداشت، نشانک، وضعیت/پیشرفت مطالعه، تالار گفتگو و اعلان‌های شخصی نیازمند ورود است؛ کلاس خصوصی علاوه بر ورود به عضویت معتبر نیاز دارد.
- این تغییر UI به‌تنهایی تأیید امنیت API یا تکمیل قابلیت‌ها نیست. هر endpoint تعاملی آینده باید احراز هویت و مجوز محتوای مرتبط را در Backend بررسی کند.
- قابلیت هایلایت موقت وب در حال CI است و هنوز پذیرش کاربری نشده است. کاتالوگ مهمان اندروید نیز در مسیر جداگانه در حال CI است؛ فعال بودن آن در APK به پیکربندی URL بک‌اند در زمان build وابسته است.
- قابلیت پرسش هوش مصنوعی در این مسیر وب/Backend مشترک هنوز پیاده‌سازی نشده؛ بنابراین فعلاً فقط سیاست دسترسی آن مشخص شده و نباید آن را قابلیت آماده معرفی کرد.

### تکمیل آزمون‌های مرز ورود — 2026-11-02

- برای endpointهای یادداشت شخصی، وضعیت/نشانک و موقعیت پخش، تالار گفتگو، اعلان‌ها و فهرست یادداشت‌های من آزمون HTTP اضافه شد؛ درخواست بدون توکن باید 401 Unauthorized دریافت کند.
- آزمون‌های مصنوعی و build وب/بک‌اند برای commit 9830e634d9cb4e1a08da06240dfeb354e3084e3e موفق شدند.
- ساخت Debug APK برای تغییر کاتالوگ مهمان و امکان انتخاب متن عمومی در commit 694492168289ca27d83d9ea24c48b0bb4e4d43fc موفق شد.
- این نتایج فقط build و آزمون‌های خودکارند؛ آزمون دستی روی دستگاه و اتصال به نشانی واقعی Backend هنوز انجام نشده است.


## قرارداد امن پرسش هوش مصنوعی — 2026-11-02

- در commit `ce4cf6b62b8a38dfdbb27799a4860c5ac5bbc1e2` قرارداد پیشنهادی مسیر پرسش از هوش مصنوعی در سند API ثبت شد؛ مسیر فقط برای کاربر احراز هویت‌شده و دارای مجوز مشاهده همان جلسه است.
- برای کلاس خصوصی، عضویت فعال باید در Backend بررسی شود؛ مهمان نباید بتواند درخواست مدل ارسال کند یا با تفاوت خطاها وجود محتوای خصوصی را تشخیص دهد.
- متن کامل جلسه از کلاینت ارسال نمی‌شود؛ Backend باید متن معتبر را پس از کنترل دسترسی از مخزن جلسه دریافت کند. متن انتخابی و سؤال محدودیت طول دارند.
- کلید ارائه‌دهنده فقط در secret محیط Backend قرار می‌گیرد؛ انتخاب ارائه‌دهنده/مدل هنوز نهایی نشده است. کلیدهای Gemini/Groq ذخیره‌شده روی Android نباید به Web یا Backend منتقل یا در کلاینت مشترک استفاده شوند.
- ثبت transcript، سؤال یا پاسخ خصوصی در log ممنوع است؛ rate limit، سقف مصرف، timeout و پاک‌سازی خطاها پیش‌شرط فعال‌سازی‌اند.
- **وضعیت:** این مرحله فقط قرارداد و الزامات امنیتی را مشخص کرده است؛ endpoint، اتصال provider و UI پرسش هنوز پیاده‌سازی نشده‌اند. تا انتخاب/تنظیم امن provider و افزودن آزمون‌های مجوز و محدودیت مصرف، قابلیت فعال نمی‌شود.
## درگاه محافظت‌شده پرسش هوش مصنوعی — 2026-11-02

- قرارداد HTTP اولیه در backend اضافه شد: `POST /api/v1/classes/{classId}/sessions/{sessionId}/ai/answer`.
- مسیر فقط زیر احراز هویت Firebase قرار دارد؛ شناسه‌های کلاس و جلسه اعتبارسنجی می‌شوند و متن جلسه فقط از مخزن سرور و پس از `findVisible` خوانده می‌شود.
- طول پرسش به ۴۰۰۰ و متن انتخاب‌شده به ۱۲۰۰۰ نویسه محدود شده است؛ متن کامل جلسه از کلاینت پذیرفته نمی‌شود.
- مرز مستقل `AiAnswerService` ایجاد شده است تا انتخاب ارائه‌دهنده به Ktor و منطق مجوزها گره نخورد.
- پیاده‌سازی پیش‌فرض عمداً غیرفعال است و تا زمان نصب adapter و تنظیم امن secretهای سمت سرور، پاسخ `503 ai_service_unavailable` می‌دهد.
- اتصال Gemini/Groq، محدودسازی نرخ و هزینه، timeout، اعتبارسنجی ارجاعات، رابط وب/اندروید و آزمون دستی هنوز انجام نشده‌اند؛ قابلیت پرسش هوش مصنوعی آماده استفاده نیست.
- آزمون‌های HTTP برای رد درخواست بدون ورود و اعتبارسنجی شناسه‌ها اضافه شده‌اند؛ نتیجه CI این commit باید جداگانه بررسی شود.


### قابل‌آزمایش‌کردن مرز سرویس هوش مصنوعی — 2026-11-02

- در commit `6253a2f7b66e278bb09dca361d2c29787b5371b1` سرویس پاسخ هوش مصنوعی به‌صورت پارامتر قابل تزریق به راه‌اندازی Ktor درآمد؛ مسیر production همچنان به‌طور پیش‌فرض از `DisabledAiAnswerService` استفاده می‌کند.
- در commit `7975f63cd4885b9241a77974e3749cce7727bf90` آزمون واحد اضافه شد تا غیرفعال‌بودن سرویس پیش‌فرض و عدم تولید پاسخ ساختگی را تثبیت کند.
- این تغییر فقط امکان تزریق آداپتور و آزمون‌پذیری را فراهم می‌کند؛ هیچ ارائه‌دهنده‌ای فعال نشده و endpoint همچنان تا پیکربندی امن provider پاسخ 503 می‌دهد.
- آزمون CI برای آخرین commit باید جداگانه بررسی شود.


### آزمون مرزهای ورودی درخواست AI — 2026-11-02

- در commit `48eb4da3b7ef56a0827722c372cdea1e2789b642` آزمون‌های HTTP برای سؤال خالی، سؤال بیش از ۴۰۰۰ نویسه و متن انتخابی بیش از ۱۲۰۰۰ نویسه افزوده شد.
- این آزمون‌ها از DataSource غیرقابل‌استفاده استفاده می‌کنند؛ بنابراین انتظار دارند اعتبارسنجی ورودی پیش از هر دسترسی به پایگاه داده انجام شود.
- این آزمون‌ها جایگزین آزمون یکپارچه سیاست دسترسی کلاس خصوصی/عضویت نیستند؛ برای آن، محیط PostgreSQL آزمون و داده‌های fixture لازم است.
