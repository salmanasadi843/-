# درس‌یار — ماتریس مرجع دسترسی API (نسخه ممیزی)

تاریخ: 2026-11-06  
شاخه: `architecture/shared-backend-and-web`  
هدف: جمع‌آوری endpointها و خلاصه قواعد نقش برای تطبیق با آزمون‌های HTTP؛ این سند **جایگزین مرحله 08 یا تست/پذیرش نیست**. «موجود در آزمون» فقط در جایی نوشته شده که از بررسی قبلی/CI ثبت‌شده شواهد داریم؛ سایر خانه‌ها نیازمند تطبیق موردی هستند.

## نقش‌ها
- **G**: مهمان، بدون Firebase token
- **S0**: دانشجوی غیرعضو
- **S1**: دانشجوی عضو فعال کلاس خصوصی
- **T0**: استاد غیرمالک
- **T1**: استاد مالک
- **A**: مدیر اصلی (super_admin)

قاعده کلی: نقش از PostgreSQL می‌آید؛ UID ناشناخته یا حساب غیرفعال رد می‌شود. مالکیت و عضویت در سطح کلاس/منبع تعیین‌کننده‌اند.

## مسیرهای عمومی و هویت
| مسیر | G | S0/S1/T0/T1/A | عملیات/قاعده | وضعیت شواهد |
|---|---|---|---|---|
| `GET /health/live`, `/health/ready`, `GET /api/v1` | مجاز | مجاز | health/version | health CI |
| `GET /api/v1/public/classes` | مجاز | مجاز | فقط کلاس عمومی منتشرشده | آزمون/CI قبلی |
| `GET /api/v1/public/classes/{c}/sessions` | مجاز | مجاز | فقط جلسه منتشرشده در کلاس عمومی | آزمون HTTP |
| `GET /api/v1/public/classes/{c}/sessions/{s}` | مجاز | مجاز | تطبیق هم‌زمان classId/sessionId؛ غیرقابل‌دسترسی=404 | آزمون HTTP |
| `GET /api/v1/me`, `GET /api/v1/me/notes` | 401 | احراز‌شده | هویت/یادداشت‌های خود کاربر | آزمون جداسازی یادداشت در مسیر جلسه؛ آزمون HTTP مهمان/دانشجو/مالک در commit `62779833` افزوده شد؛ Backend CI روی head `ae937121e7` موفق |
| `POST /api/v1/classes/{c}/sessions/{s}/ai/answer` | 401 | احراز‌شده + جلسه قابل مشاهده | AI؛ در سرویس غیرفعال 503 | نیاز به تطبیق ماتریس |

## استاد/کلاس/جلسه/ساختار
| منبع و مسیرها | G | S0 | S1 | T0 | T1 | A | پوشش ثبت‌شده |
|---|---:|---:|---:|---:|---:|---:|---|
| فهرست استادها `GET /api/v1/teachers` | 401 | auth | auth | auth | auth | auth | نیازمند بررسی جزئی |
| کلاس‌ها `GET/POST /api/v1/classes`، `GET /deleted`، `PATCH/{c}`، `DELETE/{c}`، `POST/{c}/restore`، `POST/{c}/reorder` | 401 | عمومی منتشرشده/محدود | عضویت برای خصوصی | عمومی read؛ write فقط مالک | read/write مالک | مدیریت همه | آزمون HTTP کلاس |
| پوشه‌ها `GET/POST/PATCH/DELETE /api/v1/teacher-folders` | 401 | فقط موارد قابل‌مشاهده | موارد قابل‌مشاهده | موارد قابل‌مشاهده؛ write مالک | write مالک | مدیریت بین‌استادی | آزمون HTTP پوشه؛ CI سبز |
| دوره‌ها `/api/v1/classes/{c}/courses`, `/api/v1/courses/{id}` | 401 | وابسته به دسترسی کلاس | عضو خصوصی | read عمومی؛ write مالک | write مالک | مدیریت همه | آزمون HTTP دوره |
| جلسه‌ها `/api/v1/classes/{c}/sessions`, `/api/v1/sessions`, `/deleted`, `/{s}`, `/move`, `/reorder`, `/restore` | 401 | عمومی منتشرشده | عضو خصوصی | read عمومی؛ write مالک | write مالک | مدیریت همه | آزمون HTTP جلسه |

## عضویت، کد ورود، محتوای جانبی
| مسیر | قاعده مورد انتظار | پوشش ثبت‌شده/وضعیت |
|---|---|---|
| `GET/POST /api/v1/classes/{c}/access-codes`، `/api/v1/access-codes/{id}` | مالک/مدیر مدیریت؛ دانشجو و استاد غیرمالک منع؛ redeem برای دانشجو | آزمون HTTP کد ورود؛ CI آخرین head سبز |
| `GET/DELETE /api/v1/classes/{c}/memberships/{student}`، `POST /api/v1/class-memberships/redeem` | مالک/مدیر مدیریت؛ غیرمالک منع؛ پس از لغو دسترسی خصوصی قطع | آزمون‌های عضویت ثبت‌شده؛ تطبیق نهایی |
| `/api/v1/classes/{c}/sessions/{s}/notes`، `/api/v1/session-notes/{id}` | یادداشت فقط برای صاحب یادداشت؛ دسترسی جلسه باید معتبر باشد | آزمون HTTP یادداشت/حریم شخصی |
| `/api/v1/classes/{c}/sessions/{s}/state` | وضعیت پخش/علاقه‌مندی مختص کاربر | آزمون HTTP جداسازی کاربر |
| `/api/v1/sessions/{s}/audio-sources`، `/{sourceId}` | مشاهده مطابق دسترسی جلسه؛ تغییر مالک/مدیر؛ منع دانشجو/استاد غیرمالک | آزمون HTTP منابع صوتی |
| `/api/v1/classes/{c}/announcements`، `/{announcementId}` | مشاهده طبق دسترسی کلاس؛ انتشار/حذف مالک یا مدیر | آزمون HTTP اعلان |
| `/api/v1/classes/{c}/forum/topics`، `/api/v1/forum/topics/{id}`، `/posts`، `/api/v1/forum/posts/{id}`، `/moderation` | دسترسی خواندن طبق کلاس/عضویت؛ نویسنده و مدیر/مالک قواعد ویرایش/حذف/moderation | آزمون HTTP forum در head d114 سبز |
| `GET /api/v1/notifications`، `POST /{id}/read` | فقط اعلان‌های کاربر و علامت‌گذاری همان کاربر | آزمون HTTP اعلان در head d114 سبز |
| `POST /api/v1/teacher-applications`، `GET/PATCH /api/v1/admin/teacher-applications` | ثبت درخواست احراز‌شده؛ فهرست/تصمیم فقط مدیر | آزمون HTTP نقش‌ها و چرخه submit/approve/pending removal/invalid decision/missing ID در `TeacherAdministrationRoutesAuthorizationIntegrationTest`؛ Backend CI روی head `ae937121e7` موفق |

## شکاف‌های باقی‌مانده برای مرحله 08
1. برای هر route بالا، assertion مشخص **401 مهمان، 403 نقش نامجاز، 404 منبع خارج از scope، و موفقیت نقش مجاز** را با تست واقعی تطبیق دهیم؛ «auth» در جدول هنوز پاسخ دقیق هر متد نیست.
2. routeهای چندمتدی و nested (به‌خصوص access-code، memberships، courses، forum posts/topic، notifications و teacher applications) با فهرست متدهای واقعی در `Application.kt` ریز شوند؛ از نام route به‌تنهایی نتیجه مجوز استنتاج نشود.
3. در تست‌های موجود، پوشش hide/move/reorder/delete/restore و خطاهای مالکیت/والد/چرخه برای folder/class/session تطبیق شود.
4. موارد ناقص فقط با آزمون HTTP هدفمند تکمیل شوند؛ هیچ مجوز یا رفتار محصولی بر مبنای این سند تغییر نکند.
5. مرحله 08 فقط پس از ماتریس کامل + CI موفق قابل تیک است. مرحله 09 پذیرش staging و 10 امنیت نهایی جدا باقی می‌مانند.

## CI مرتبط
- Backend: [42146972919](https://github.com/salmanasadi843/-/actions/runs/42146972919) — success، head `d114d636`
- Android: [42146972962](https://github.com/salmanasadi843/-/actions/runs/42146972962) — success
- Web: [42146972974](https://github.com/salmanasadi843/-/actions/runs/42146972974) — success
- Legacy Inventory: [42146972918](https://github.com/salmanasadi843/-/actions/runs/42146972918) — success

**Roadmap رسمی: 17/40 (42.5٪). این سند جدید مرحله‌ای از 40 مورد را تیک نمی‌زند.**


## ممیزی تطبیقی route × method × test — 2026-11-06 (head: ae937121e7)

این بخش از روی routeهای واقعی `Application.kt` و فراخوانی‌های HTTP در فایل‌های آزمون شاخه تهیه شده است؛ «دارای آزمون» به معنی پوشش کامل همه نقش‌ها، همه پاسخ‌های خطا یا پذیرش staging نیست.

### موجودی واقعی routeها در Application.kt

| حوزه | متدها و مسیرهای واقعی | فایل آزمون HTTP مرتبط | نتیجه تطبیق |
|---|---|---|---|
| سلامت و نسخه | GET `/health/live`, `/health/ready`, `/api/v1` | Backend CI | بررسی health/version در CI؛ این‌ها endpointهای عمومی‌اند. |
| کاتالوگ عمومی | GET `/api/v1/public/classes`, `/classes/{classId}/sessions`, `/sessions/{sessionId}` | `TeachingSessionRoutesAuthorizationIntegrationTest` | مهمان و مرز انتشار عمومی/خصوصی و تطبیق classId/sessionId آزموده شده‌اند. |
| هویت و یادداشت‌های کاربر | GET `/api/v1/me`, GET `/api/v1/me/notes` | `AuthenticationTest`, `TeachingSessionRoutesAuthorizationIntegrationTest` | هویت نامعتبر/غیرفعال و جداسازی فهرست یادداشت شخصی آزموده شده؛ CI فعلی سبز است. |
| پاسخ AI | POST `/api/v1/classes/{classId}/sessions/{sessionId}/ai/answer` | `AiAnswerServiceTest` (سرویس) | **شکاف HTTP:** آزمون route برای 401 مهمان، 404 جلسه خارج از دسترس، نقش‌های مجاز و پاسخ 503 سرویس در دسترس نبودن پیدا نشد. آزمون سرویس به‌تنهایی پوشش HTTP نیست. |
| درخواست استاد | POST `/api/v1/teacher-applications`; GET/PATCH `/api/v1/admin/teacher-applications` | `TeacherAdministrationRoutesAuthorizationIntegrationTest` | مهمان/دانشجو/استاد غیرمدیر و مدیر، submit، approve، حذف از pending، تصمیم نامعتبر و شناسه ناموجود آزموده شده‌اند. |
| فهرست استادها | GET `/api/v1/teachers` | `ClassManagementRouteIntegrationTest` | درخواست HTTP وجود دارد؛ ماتریس پاسخ‌های مهمان/دانشجو/استاد/مدیر و حالت شناسه نامعتبر به‌صورت مستقل و کامل مستند نشده است. |
| پوشه استاد | GET/POST `/api/v1/teacher-folders`; PATCH/DELETE `/api/v1/teacher-folders/{folderId}` | `TeacherFolderManagementRouteIntegrationTest`, `TeachingSessionRoutesAuthorizationIntegrationTest` | مالک/مدیر، استاد غیرمالک، ساخت برای مالک دیگر، والد/چرخه، مخفی‌سازی، حذف پوشه خالی و منع حذف پوشه دارای فرزند پوشش دارند؛ ماتریس جامع پاسخ‌های 401/403/404 برای همه متدها هنوز یکپارچه نیست. |
| کد ورود | GET/POST `/api/v1/classes/{classId}/access-codes`; DELETE `/api/v1/access-codes/{codeId}`; POST `/api/v1/class-memberships/redeem` | `ClassAccessCodeRoutesIntegrationTest` | مالک/مدیر/استاد غیرمالک/دانشجو/مهمان و redeem دانشجو، کد غیرفعال و کلاس عمومی آزموده شده‌اند؛ redeem با نقش استاد و همه حالت‌های منبع ناموجود هنوز پوشش صریح ندارند. |
| عضویت | GET/DELETE `/api/v1/classes/{classId}/memberships` | `TeachingSessionRoutesAuthorizationIntegrationTest` | مالک/مدیر مجاز، استاد غیرمالک/دانشجو/مهمان ممنوع و قطع دسترسی پس از لغو عضویت آزموده شده‌اند. |
| کلاس | GET `/api/v1/classes`; GET `/deleted`; POST؛ POST `/{classId}/reorder`; PATCH/DELETE `/{classId}`; POST `/{classId}/restore` | `ClassManagementRouteIntegrationTest`, `TeachingSessionRoutesAuthorizationIntegrationTest` | فهرست، ساخت، ویرایش، ترتیب، حذف و بازیابی و مرز مالک/مدیر/استاد غیرمالک پوشش دارند. **شکاف HTTP مشخص:** GET `/api/v1/classes/deleted` در فراخوانی‌های آزمون route فعلی مشاهده نشد. |
| دوره | GET/POST `/api/v1/classes/{classId}/courses`; PATCH/DELETE `/api/v1/courses/{courseId}` | `CourseManagementRouteIntegrationTest`, `TeachingSessionRoutesAuthorizationIntegrationTest` | فهرست/ایجاد/ویرایش/حذف و مرز مالک/مدیر/دانشجو/استاد غیرمالک آزموده شده‌اند؛ آزمون مستقل GET فهرست دوره برای مهمان/عضو/غیرعضو به ماتریس کامل نیاز دارد. |
| جلسه | GET/POST `/api/v1/classes/{classId}/sessions`; GET `/{sessionId}`; GET `/api/v1/sessions/deleted`; PATCH/DELETE `/api/v1/sessions/{sessionId}`; POST `/move`, `/reorder`, `/restore` | `TeachingSessionRoutesAuthorizationIntegrationTest`, `SessionManagementRouteIntegrationTest` | فهرست/جزئیات، ایجاد، ویرایش، حذف، انتقال، ترتیب و بازیابی برای سناریوهای مالک/مدیر/استاد غیرمالک و مشاهده عمومی/عضویت پوشش دارند. **شکاف HTTP مشخص:** GET `/api/v1/sessions/deleted` در فراخوانی‌های آزمون route فعلی مشاهده نشد. |
| یادداشت جلسه | GET/POST `/api/v1/classes/{classId}/sessions/{sessionId}/notes`; PATCH/DELETE `/api/v1/session-notes/{noteId}` | `TeachingSessionRoutesAuthorizationIntegrationTest` | مالکیت یادداشت و منع مشاهده/ویرایش/حذف یادداشت کاربر دیگر آزموده شده؛ فهرست شخصی نیز برای مهمان/دانشجو/مالک آزمون دارد. |
| وضعیت جلسه | GET/PUT `/api/v1/classes/{classId}/sessions/{sessionId}/state` | `TeachingSessionRoutesAuthorizationIntegrationTest` | جداسازی وضعیت کاربرها آزموده شده است. |
| منابع صوتی | GET/POST `/api/v1/sessions/{sessionId}/audio-sources`; PATCH/DELETE `/{sourceId}` | `TeachingSessionRoutesAuthorizationIntegrationTest` | مشاهده و تغییر برای دانشجو/مالک/مدیر/استاد غیرمالک و منع تغییر غیرمجاز آزموده شده است. |
| اطلاعیه | GET/POST `/api/v1/classes/{classId}/announcements`; DELETE `/{announcementId}` | `TeachingSessionRoutesAuthorizationIntegrationTest` | مشاهده عمومی/خصوصی و عضویت، انتشار و حذف توسط مالک/مدیر و منع نقش‌های غیرمجاز آزموده شده است. |
| تالار گفتگو | GET/POST topics؛ GET/POST posts؛ PATCH/DELETE topic و post؛ PATCH moderation | `TeachingSessionRoutesAuthorizationIntegrationTest` | ساخت/فهرست/پاسخ/ویرایش/حذف، moderation، قفل موضوع و مالکیت اعلان‌ها آزموده شده‌اند. ماتریس جداگانه 401/403/404 برای تک‌تک متدها هنوز لازم است. |
| اعلان کاربر | GET `/api/v1/notifications`; POST `/{notificationId}/read` | `TeachingSessionRoutesAuthorizationIntegrationTest` | فهرست فقط اعلان‌های خود کاربر و منع علامت‌گذاری اعلان کاربر دیگر آزموده شده است. |

### شکاف‌های اثبات‌شده و ترتیب رفع

1. **P0 — AI HTTP route:** آزمون integration برای guest=401، هویت مجاز با جلسه غیرقابل‌مشاهده=404، جلسه قابل‌مشاهده و سرویس خاموش=503، و پاسخ موفق/خطای provider اضافه شود. پیش از نوشتن تست، fixture و الگوی testApplication موجود در آزمون‌های فعلی reuse شود.
2. **P1 — فهرست حذف‌شده‌ها:** برای GET `/api/v1/classes/deleted` و GET `/api/v1/sessions/deleted` آزمون HTTP مهمان=401، استاد مالک/مدیر=200 و استاد غیرمالک/دانشجو=403 (یا پاسخ دقیق قرارداد repository) اضافه/تأیید شود. پاسخ مورد انتظار باید از repository و رفتار واقعی route تثبیت شود، نه حدس.
3. **P1 — تکمیل خانه‌های ماتریس:** برای `/api/v1/teachers`، redeem کد ورود و فهرست دوره‌ها، پاسخ نقش‌های نامجاز و منابع خارج از scope به‌صورت صریح ثبت شود.
4. **P1 — یکپارچه‌سازی:** برای routeهای دارای آزمون، statusهای 401/403/404/موفقیت را متدبه‌متد علامت‌گذاری کن؛ عنوان کلی «آزمون HTTP موجود» به معنی پوشش تمام خانه‌ها نیست.

### وضعیت تأیید CI برای شواهد این ممیزی

- شاخه: `architecture/shared-backend-and-web`
- head تأییدشده: `ae937121e7cb426e4a6914e8cc3e4f199296267a`
- Backend CI: [42170575841 — success](https://github.com/salmanasadi843/-/actions/runs/42170575841)
- Android APK: [42170575935 — success](https://github.com/salmanasadi843/-/actions/runs/42170575935)
- Web: [42170575811 — success](https://github.com/salmanasadi843/-/actions/runs/42170575811)
- Legacy Inventory: [42170575774 — success](https://github.com/salmanasadi843/-/actions/runs/42170575774)

**Roadmap رسمی بدون تغییر: 17/40 (42.5٪).** این ممیزی و سبز بودن CI هیچ‌یک به‌تنهایی مرحله 08 را کامل نمی‌کنند؛ staging مرحله 09 و بازبینی امنیتی مرحله 10 مستقل باقی می‌مانند.
