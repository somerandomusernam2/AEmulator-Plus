<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**Старые прошивки Android — HTC Sense, TouchWiz, MIUI, AOSP — на современном телефоне. Без root и без ПК.**

[🇬🇧 English](../../README.md) · **🇷🇺 Русский** · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus загружает настоящую систему Android 2.3–7.x прямо из файла прошивки: ZIP для рекавери, архива Odin или factory-образа Google. Старый ARM-код транслирует доработанный QEMU, binder ядра эмулируется, графика идёт через GPU телефона, звук — через аудиосистему Android. Всё работает внутри обычного приложения.

## ✨ Возможности

- Импорт почти любых форматов: ZIP для CWM/TWRP, Odin `.tar.md5` от Samsung, factory `.tgz` от Google, `system.img`, OTA `system.new.dat.br`
- Оболочки производителей работают как есть: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Аппаратная графика через GL-мост, звук, касания и мультитач, сеть с современным TLS-прокси
- Общая папка «карты памяти» для APK, музыки и фото
- Интерфейс Material 3 Expressive на 18 языках
- Бесплатно и с открытым кодом (GPL-3.0)

## 🚀 Быстрый старт

1. Скачайте APK из [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) и установите.
2. Откройте AEmulator Plus → **Добавить прошивку** и выберите файл. Импорт занимает несколько минут.
3. Нажмите **Запустить**. Первая загрузка дольше: система оптимизирует приложения.
4. Меню ⋮ — громкость, кнопка питания и журнал; кнопка ⚙️ — настройки приложения и язык.

## 📋 Требования

- Android 8.0+ на 64-битном ARM-телефоне (arm64-v8a)
- Около 1–3 ГБ свободного места на прошивку
- Рекомендуется современный Snapdragon / Dimensity / Tensor

## ⚙️ Как это устроено

Каждый процесс гостя работает под доработанным QEMU в режиме пользователя. Демон binder заменяет драйвер ядра, GL-мост передаёт вызовы OpenGL ES на GPU телефона, а небольшие гостевые библиотеки (звуковой HAL, обёртка audio policy, прослойка LD_PRELOAD) подгоняют код производителей под эмулятор. Импортёр читает прошивку, находит init-скрипты в boot-образе и строит план запуска системных служб.

## 🛠️ Сборка из исходников

Проверенная сборка релизного приложения использует JDK 21 и Android SDK 36. См. [инструкцию по сборке и исходникам](../build-source.md) — там же детали нативного тулчейна и подписи. **У унаследованных готовых бинарников движка пока нет подтверждённого полного происхождения исходников и сборки**; см. [аудит](../license-audit.md).

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 Благодарности

AEmulator Plus вырос из эмуляторов HTC Desire HD и HTC One M7 [первого автора](https://t.me/istratiit_ech) — без его движка проекта бы не было.

Это модифицированный форк [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), который, в свою очередь, является модифицированным форком [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Ссылки

- Мой форк: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Апстрим: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Оригинал: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Автор: [somerandomusername2](https://github.com/somerandomusernam2)
- Первый автор: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Лицензия

GPL-3.0. Android, товарные знаки и прошивки принадлежат их владельцам.

См. [датированные уведомления об изменениях](../../NOTICE.md) и [незакрытый аудит исходников и лицензий](../license-audit.md). Метка GPL не гарантирует, что у каждого встроенного готового бинарника есть полные соответствующие исходники.
