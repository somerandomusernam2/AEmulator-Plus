<div dir="rtl">

<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**فرم‌ویرهای کلاسیک اندروید — HTC Sense، TouchWiz، MIUI، AOSP — روی گوشی مدرن. بدون روت و بدون رایانه.**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · **🇮🇷 فارسی** · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus یک سیستم واقعی اندروید 2.3 تا 7.x را مستقیم از فایل فرم‌ویر بوت می‌کند: ZIP ریکاوری، آرشیو Odin یا ایمیج کارخانهٔ گوگل. کد قدیمی ARM با QEMU اصلاح‌شده ترجمه می‌شود، binder هسته شبیه‌سازی می‌شود، گرافیک با GPU گوشی و صدا با سامانهٔ صوتی اندروید پخش می‌شود — همه درون یک برنامهٔ معمولی.

## ✨ ویژگی‌ها

- درون‌ریزی تقریباً همهٔ قالب‌ها: ‏ZIP ‏CWM/TWRP، ‏Odin ‏`.tar.md5` سامسونگ، ایمیج کارخانهٔ گوگل ‏`.tgz`، ‏`system.img`، ‏OTA ‏`system.new.dat.br`
- رابط‌های سازندگان همان‌طور که هستند کار می‌کنند: HTC Sense، Samsung TouchWiz، MIUI، AOSP
- گرافیک سخت‌افزاری از طریق پل GL، صدا، لمس و چندلمسی، شبکه با پراکسی TLS مدرن
- پوشهٔ مشترک کارت حافظه برای APK، موسیقی و عکس
- رابط Material 3 Expressive به 18 زبان
- رایگان و متن‌باز (GPL-3.0)

## 🚀 شروع سریع

1. فایل APK را از [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) دانلود و نصب کنید.
2. AEmulator Plus را باز کنید ← **افزودن فرم‌ویر** و فایل را انتخاب کنید. درون‌ریزی چند دقیقه طول می‌کشد.
3. **اجرا** را بزنید. بوت اول کندتر است: سیستم برنامه‌ها را بهینه می‌کند.
4. منوی ⋮ برای صدا، دکمهٔ پاور و گزارش؛ دکمهٔ ⚙️ برای تنظیمات و زبان.

## 📋 نیازمندی‌ها

- اندروید 8.0+ روی گوشی ARM ‏64 بیتی (arm64-v8a)
- حدود 1 تا 3 گیگابایت فضای آزاد برای هر فرم‌ویر
- پیشنهاد: Snapdragon / Dimensity / Tensor جدید

## ⚙️ چگونه کار می‌کند

هر فرایند مهمان زیر یک QEMU اصلاح‌شده در حالت کاربر اجرا می‌شود. سرویس binder جای درایور هسته را می‌گیرد، پل GL فراخوانی‌های OpenGL ES را به GPU می‌فرستد و کتابخانه‌های کوچک مهمان (HAL صوتی، پوشش audio policy، لایهٔ LD_PRELOAD) کد سازندگان را با شبیه‌ساز سازگار می‌کنند. درون‌ریز فرم‌ویر را می‌خواند، اسکریپت‌های init را در ایمیج boot پیدا می‌کند و برنامهٔ اجرای سرویس‌ها را می‌سازد.

## 🛠️ ساخت از کد منبع

ساخت انتشار آزموده‌شدهٔ برنامه از JDK 21 و Android SDK 36 استفاده می‌کند. [راهنمای ساخت و کد منبع](../build-source.md) را ببینید که جزئیات زنجیرهٔ ابزار بومی و امضا را دارد. **برای باینری‌های آمادهٔ موتورِ به‌ارث‌رسیده هنوز منشأ کامل کد منبع و ساخت راستی‌آزمایی نشده است**؛ [ممیزی](../license-audit.md) را ببینید.

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 سپاس

AEmulator Plus از شبیه‌سازهای HTC Desire HD و HTC One M7 ‏[سازندهٔ نخست](https://t.me/istratiit_ech) رشد کرد — بدون موتور او این پروژه وجود نداشت.

این یک فورک تغییریافته از [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) است که خود فورک تغییریافته‌ای از [uxazu/AEmulator](https://github.com/uxazu/AEmulator) است.

## 🔗 پیوندها

- فورک من: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- مخزن بالادستی: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- اصلی: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- سازنده: [somerandomusername2](https://github.com/somerandomusernam2)
- سازندهٔ نخست: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 مجوز

GPL-3.0. اندروید، نشان‌های تجاری و فرم‌ویرها متعلق به صاحبانشان‌اند.

[اطلاعیه‌های تاریخ‌دار تغییرات](../../NOTICE.md) و [ممیزی ناتمام کد منبع و مجوزها](../license-audit.md) را ببینید. برچسب GPL گواه آن نیست که هر باینری همراه، کد منبع کامل و منطبق دارد.

</div>
