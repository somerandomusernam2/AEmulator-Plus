<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**Klassische Android-Firmware — HTC Sense, TouchWiz, MIUI, AOSP — auf einem modernen Handy. Ohne Root, ohne PC.**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · **🇩🇪 Deutsch** · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus startet ein echtes Android-2.3–7.x-System direkt aus einer Firmware-Datei: Recovery-ZIP, Odin-Archiv oder Google-Factory-Image. Der alte ARM-Code läuft über ein angepasstes QEMU, der Binder des Kernels wird emuliert, die Grafik läuft über die GPU des Handys und der Ton über Androids Audiosystem — alles in einer normalen App.

## ✨ Funktionen

- Import fast aller Formate: CWM/TWRP-ZIP, Samsung Odin `.tar.md5`, Google Factory `.tgz`, `system.img`, OTA `system.new.dat.br`
- Hersteller-Oberflächen laufen unverändert: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Hardwaregrafik über die GL-Brücke, Ton, Touch und Multitouch, Netzwerk mit modernem TLS-Proxy
- Gemeinsamer Speicherkarten-Ordner für APKs, Musik und Fotos
- Material-3-Expressive-Oberfläche in 18 Sprachen
- Kostenlos und quelloffen (GPL-3.0)

## 🚀 Schnellstart

1. APK unter [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) herunterladen und installieren.
2. AEmulator Plus öffnen → **Firmware hinzufügen** und die Datei wählen. Der Import dauert einige Minuten.
3. **Starten** drücken. Der erste Start dauert länger: Das System optimiert Apps.
4. Menü ⋮ für Lautstärke, Ein/Aus und Protokoll; ⚙️ öffnet Einstellungen und Sprache.

## 📋 Voraussetzungen

- Android 8.0+ auf einem 64-Bit-ARM-Handy (arm64-v8a)
- Etwa 1–3 GB freier Speicher pro Firmware
- Empfohlen: aktueller Snapdragon / Dimensity / Tensor

## ⚙️ So funktioniert es

Jeder Gastprozess läuft unter einem angepassten User-Mode-QEMU. Ein Binder-Daemon ersetzt den Kernel-Treiber, eine GL-Brücke leitet OpenGL-ES-Aufrufe an die GPU weiter, und kleine Gast-Bibliotheken (Audio-HAL, Audio-Policy-Wrapper, LD_PRELOAD-Shim) passen Herstellercode an den Emulator an. Der Importer liest die Firmware, findet die Init-Skripte im Boot-Image und erstellt einen Startplan für die Systemdienste.

## 🛠️ Aus dem Quellcode bauen

Der getestete Release-Build der App verwendet JDK 21 und Android SDK 36. Siehe die [Build-/Quellcode-Anleitung](../build-source.md) mit Details zur nativen Toolchain und zum Signieren. **Für die geerbten Engine-Binärdateien ist die vollständige Herkunft von Quellcode und Build noch nicht verifiziert**; siehe [das Audit](../license-audit.md).

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 Dank

AEmulator Plus ist aus den Emulatoren für HTC Desire HD und HTC One M7 [des Originalautors](https://t.me/istratiit_ech) hervorgegangen — ohne seine Engine gäbe es dieses Projekt nicht.

Dies ist ein modifizierter Fork von [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), das wiederum ein modifizierter Fork von [uxazu/AEmulator](https://github.com/uxazu/AEmulator) ist.

## 🔗 Links

- Mein Fork: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Original: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Originalautor: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Lizenz

GPL-3.0. Android, Marken und Firmware gehören ihren Inhabern.

Siehe die [datierten Änderungshinweise](../../NOTICE.md) und das [offene Quellcode-/Lizenz-Audit](../license-audit.md). Das GPL-Label ist keine Bestätigung, dass für jede mitgelieferte Binärdatei der vollständige passende Quellcode vorliegt.
