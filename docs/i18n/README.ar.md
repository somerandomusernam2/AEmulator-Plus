<div dir="rtl">

<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**شغّل برامج أندرويد الكلاسيكية — HTC Sense وTouchWiz وMIUI وAOSP — على هاتف حديث. بلا روت وبلا حاسوب.**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · **🇸🇦 العربية** · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

يقلع AEmulator Plus نظام أندرويد 2.3–7.x حقيقيًا مباشرة من ملف البرنامج الثابت: ملف ZIP للريكفري أو أرشيف Odin أو صورة مصنع Google. تُترجم شفرة ARM القديمة عبر QEMU معدّل، ويُحاكى binder النواة، وتُرسم الواجهة بمعالج رسومات الهاتف ويمر الصوت عبر نظام صوت أندرويد — كل ذلك داخل تطبيق عادي.

## ✨ المزايا

- استيراد كل الصيغ تقريبًا: ‏ZIP لـ CWM/TWRP، و‏Odin ‏`.tar.md5` من سامسونج، وصورة مصنع Google ‏`.tgz`، و`system.img`، وOTA ‏`system.new.dat.br`
- واجهات الشركات تعمل كما هي: HTC Sense وSamsung TouchWiz وMIUI وAOSP
- رسوميات عتادية عبر جسر GL، وصوت، ولمس ولمس متعدد، وشبكة مع وكيل TLS حديث
- مجلد بطاقة ذاكرة مشترك لملفات APK والموسيقى والصور
- واجهة Material 3 Expressive بـ 18 لغة
- مجاني ومفتوح المصدر (GPL-3.0)

## 🚀 البدء السريع

1. نزّل ملف APK من [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) وثبّته.
2. افتح AEmulator Plus ← **إضافة برنامج ثابت** واختر الملف. يستغرق الاستيراد بضع دقائق.
3. اضغط **تشغيل**. الإقلاع الأول أبطأ: النظام يحسّن التطبيقات.
4. القائمة ⋮ للصوت وزر التشغيل والسجل؛ وزر ⚙️ للإعدادات واللغة.

## 📋 المتطلبات

- أندرويد 8.0+ على هاتف ARM بمعمارية 64 بت (arm64-v8a)
- نحو 1–3 غيغابايت لكل برنامج ثابت
- يُنصح بمعالج Snapdragon / Dimensity / Tensor حديث

## ⚙️ كيف يعمل

تعمل كل عملية ضيف تحت QEMU معدّل بوضع المستخدم. يحل خادم binder محل برنامج تشغيل النواة، وينقل جسر GL استدعاءات OpenGL ES إلى معالج الرسومات، وتكيّف مكتبات صغيرة (HAL للصوت، وغلاف audio policy، وطبقة LD_PRELOAD) شفرة الشركات مع المحاكي. يقرأ المستورد البرنامج الثابت ويجد سكربتات init في صورة boot ويبني خطة تشغيل الخدمات.

## 🛠️ البناء من المصدر

يستخدم إصدار التطبيق المُختبَر JDK 21 وAndroid SDK 36. راجع [تعليمات البناء والمصدر](../build-source.md) لمعرفة تفاصيل سلسلة الأدوات الأصلية والتوقيع. **الملفات الثنائية الجاهزة للمحرك الموروثة لا تملك بعدُ أصلاً مُتحقَّقاً منه للشيفرة المصدرية وطريقة البناء**؛ راجع [التدقيق](../license-audit.md).

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 شكر وتقدير

نشأ AEmulator Plus من محاكيات HTC Desire HD وHTC One M7 التي صنعها [المطوّر الأصلي](https://t.me/istratiit_ech)، ولولا محركه ما وُجد هذا المشروع.

هذا فرع معدَّل من [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)، وهو بدوره فرع معدَّل من [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 روابط

- فرعي: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- المستودع الأم: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- الأصل: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- المطوّر: [somerandomusername2](https://github.com/somerandomusernam2)
- المطوّر الأصلي: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 الترخيص

GPL-3.0. أندرويد والعلامات التجارية والبرامج الثابتة ملك لأصحابها.

راجع [إشعارات التعديل المؤرخة](../../NOTICE.md) و[تدقيق المصدر والتراخيص المعلَّق](../license-audit.md). وسم GPL ليس شهادة بأن لكل ملف ثنائي مضمَّن شيفرته المصدرية الكاملة المطابقة.

</div>
