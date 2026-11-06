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
| `GET /api/v1/me`, `GET /api/v1/me/notes` | 401 | احراز‌شده | هویت/یادداشت‌های خود کاربر | آزمون auth/notes |
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
| `POST /api/v1/teacher-applications`، `GET/PATCH /api/v1/admin/teacher-applications` | ثبت درخواست احراز‌شده؛ فهرست/تصمیم فقط مدیر | نیازمند تطبیق جداگانه با تست‌ها |

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
