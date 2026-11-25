<div align="center">

# پل — Pol

**تماس‌ها و پیامک‌های گوشی اندروید، روی آیفون**

Use the SIM in your Android phone from your iPhone, over Bluetooth.

<sub>from</sub><br>
<a href="https://pedicode.ir"><b>PEDICODE</b></a>

</div>

---

## چه کار می‌کند
- پیامک‌های گوشی اندروید روی آیفون، با اعلان، و جواب دادن از آیفون (انتخاب سیم ۱ یا ۲)
- تماس ورودی با صفحه‌ی اصلی تماس آیفون (CallKit)، جواب دادن، رد کردن و شماره‌گیری
- مخاطبین و تاریخچه‌ی تماس اندروید، با امکان ذخیره در مخاطبین آیفون
- یادآوری تمدید ۷ روزه‌ی SideStore
- بدون سرور: دو گوشی مستقیم با بلوتوث حرف می‌زنند

> فاز ۱: صدای تماس هنوز روی گوشی اندروید است. انتقال صدا در فاز ۳ اضافه می‌شود.

## دانلود
- اندروید: `Pol.apk` از بخش [Releases](../../releases/latest)
- آیفون: `Pol.ipa` از همان‌جا، نصب با **SideStore**
- سورس SideStore (برای نصب و آپدیت از داخل SideStore):
  `https://raw.githubusercontent.com/peadeyme/pol/main/sidestore/source.json`

## نصب روی اندروید (Android 10 به بالا)
1. `Pol.apk` را نصب کن.
2. اپ را باز کن ← **دادن مجوز**.
3. (اندروید ۱۳ به بالا) اگر مجوز پیامک خاکستری بود: اطلاعات برنامه ← منوی ⋮ ← **Allow restricted settings**، بعد دوباره مجوز بده.
4. بهینه‌سازی باتری را برای پل خاموش کن.
5. کلید اتصال را روشن کن. کد ۶ رقمی را یادداشت کن.

## نصب روی آیفون (iOS 17 به بالا)
1. SideStore را نصب کن (یک بار با کامپیوتر، با iloader).
2. در SideStore، سورس بالا را اضافه کن و **پل** را نصب کن (یا `Pol.ipa` را به SideStore Share کن).
3. پل را باز کن، گوشی اندروید را انتخاب کن و کد ۶ رقمی را وارد کن.
4. هر ۷ روز: LocalDevVPN را روشن کن و در SideStore تمدید کن. خود پل ۲ روز قبل یادآوری می‌کند.

## اشتراک
| پلن | قیمت |
|---|---|
| آزمایشی | ۷ روز رایگان |
| ماهانه | ۳۰۰٬۰۰۰ تومان |
| شش‌ماهه | ۱٬۵۰۰٬۰۰۰ تومان |
| سالانه | ۲٬۵۰۰٬۰۰۰ تومان |

فروش با **ربات تلگرام** انجام می‌شود (پوشه‌ی `bot/`): انتخاب پلن ← کد دستگاه ← کارت‌به‌کارت ← رسید در گروه مدیریت با دکمه‌ی تأیید/رد ← ربات خودش کد لایسنس را می‌فرستد. عضو شدن کاربرهای جدید هم در همان گروه اعلام می‌شود.

لایسنس آفلاین است و اپ برای بررسی آن به اینترنت نیاز ندارد. ساخت دستی لایسنس (بدون ربات):
1. مشتری «کد دستگاه» را از اپ اندروید کپی می‌کند و همراه پرداخت می‌فرستد.
2. فروشنده کد لایسنس می‌سازد:
   `python3 tools/license.py issue 7F3A2-9C41B monthly` (یا `6month` / `yearly`)
3. مشتری کد را در اپ اندروید وارد می‌کند ← «فعال‌سازی».

کد با کلید خصوصی امضا می‌شود و فقط کلید عمومی داخل اپ است؛ پس با اینکه ریپو پابلیک است، کسی نمی‌تواند لایسنس جعلی بسازد. کلید خصوصی را هرگز داخل ریپو نگذار.

## راه‌اندازی ربات تلگرام (رایگان، روی Cloudflare Workers)
1. در تلگرام با **@BotFather** یک ربات بساز و توکنش را بردار.
2. در **dash.cloudflare.com** حساب رایگان بساز. از My Profile ← API Tokens یک توکن با قالب **Edit Cloudflare Workers** بساز. Account ID را هم از صفحه‌ی اصلی Workers بردار.
3. در گیت‌هاب: Settings ← Secrets and variables ← Actions
   - **Secrets:** `CLOUDFLARE_API_TOKEN`، `CLOUDFLARE_ACCOUNT_ID`، `BOT_TOKEN`، `LICENSE_KEY` (کل متن فایل `license_private_key.pem`)، `WEBHOOK_SECRET` (یک رمز دلخواه طولانی)، `CARD_NUMBER`، `CARD_HOLDER`
   - **Variables:** `BOT_ENABLED` = `true`
4. Actions ← **Deploy bot** ← Run workflow.
5. یک بار این آدرس را باز کن: `https://pol-bot.<subdomain>.workers.dev/setup?secret=<WEBHOOK_SECRET>`
6. ربات را به گروه مدیریت اضافه کن، ادمینش کن و در گروه بنویس `/setgroup`.

## ساختار پروژه
```
android/   اپ اندروید (Kotlin, Jetpack Compose, BLE GATT server)
ios/       اپ آیفون (SwiftUI, CoreBluetooth, CallKit) — پروژه با XcodeGen ساخته می‌شود
docs/      پروتکل بلوتوث
sidestore/ سورس SideStore
bot/       ربات فروش تلگرام (Cloudflare Worker)
tools/     ساخت دستی لایسنس
```

## ساخت
همه‌چیز با GitHub Actions ساخته می‌شود (ریپو پابلیک است، پس رایگان):
- هر push به `main` ← فایل‌های `Pol.apk` و `Pol.ipa` در بخش Actions › Artifacts
- هر تگ `v*` (مثلاً `v0.1.0`) ← انتشار خودکار در Releases

### امضای ثابت APK (مهم برای آپدیت)
بدون این، هر بیلد با کلید متفاوتی امضا می‌شود و کاربر برای آپدیت باید اپ را پاک کند.
این Secrets را در Settings › Secrets and variables › Actions بساز:
`POL_KEYSTORE_B64` (فایل keystore به base64)، `POL_KEYSTORE_PASSWORD`، `POL_KEY_ALIAS`، `POL_KEY_PASSWORD`.

---

<div align="center"><sub>from</sub><br><a href="https://pedicode.ir"><b>PEDICODE</b></a></div>
