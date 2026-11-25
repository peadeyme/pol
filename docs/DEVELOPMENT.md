# یادداشت‌های توسعه

## اشتراک
۷ روز آزمایشی رایگان. خرید و تمدید: [@polantenbot](https://t.me/polantenbot)

فروش با **ربات تلگرام [@polantenbot](https://t.me/polantenbot)** انجام می‌شود (پوشه‌ی `bot/`). قیمت‌ها از پنل مدیریت ربات قابل تغییرند. مسیر خرید: انتخاب پلن ← کد دستگاه ← کارت‌به‌کارت ← رسید در گروه مدیریت با دکمه‌ی تأیید/رد ← ربات خودش کد لایسنس را می‌فرستد. عضو شدن کاربرهای جدید هم در همان گروه اعلام می‌شود.

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
- انتشار نسخه: عدد فایل `VERSION` را عوض کن و در پیام کامیت `[release]` بنویس، یا از Actions › Build › Run workflow گزینه‌ی release را تیک بزن

### امضای ثابت APK (مهم برای آپدیت)
بدون این، هر بیلد با کلید متفاوتی امضا می‌شود و کاربر برای آپدیت باید اپ را پاک کند.
این Secrets را در Settings › Secrets and variables › Actions بساز:
`POL_KEYSTORE_B64` (فایل keystore به base64)، `POL_KEYSTORE_PASSWORD`، `POL_KEY_ALIAS`، `POL_KEY_PASSWORD`.

