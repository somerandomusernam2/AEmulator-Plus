<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**Klasyczne firmware Androida — HTC Sense, TouchWiz, MIUI, AOSP — na nowoczesnym telefonie. Bez roota, bez PC.**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · **🇵🇱 Polski** · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus uruchamia prawdziwy system Android 2.3–7.x prosto z pliku firmware: ZIP do recovery, archiwum Odin lub obraz fabryczny Google. Stary kod ARM tłumaczy zmodyfikowane QEMU, binder jądra jest emulowany, grafika korzysta z GPU telefonu, a dźwięk z systemu audio Androida — wszystko w zwykłej aplikacji.

## ✨ Funkcje

- Import niemal każdego formatu: ZIP CWM/TWRP, Odin `.tar.md5` Samsunga, obraz fabryczny Google `.tgz`, `system.img`, OTA `system.new.dat.br`
- Nakładki producentów działają bez zmian: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Sprzętowa grafika przez mostek GL, dźwięk, dotyk i multitouch, sieć z nowoczesnym proxy TLS
- Wspólny folder karty pamięci na APK, muzykę i zdjęcia
- Interfejs Material 3 Expressive w 18 językach
- Za darmo i open source (GPL-3.0)

## 🚀 Szybki start

1. Pobierz APK z [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) i zainstaluj.
2. Otwórz AEmulator Plus → **Dodaj firmware** i wybierz plik. Import trwa kilka minut.
3. Naciśnij **Uruchom**. Pierwszy start trwa dłużej: system optymalizuje aplikacje.
4. Menu ⋮ — głośność, przycisk zasilania i dziennik; ⚙️ — ustawienia i język.

## 📋 Wymagania

- Android 8.0+ na 64-bitowym telefonie ARM (arm64-v8a)
- Około 1–3 GB wolnego miejsca na firmware
- Zalecany nowy Snapdragon / Dimensity / Tensor

## ⚙️ Jak to działa

Każdy proces gościa działa pod zmodyfikowanym QEMU w trybie użytkownika. Demon binder zastępuje sterownik jądra, mostek GL przekazuje wywołania OpenGL ES do GPU, a małe biblioteki gościa (HAL audio, nakładka audio policy, shim LD_PRELOAD) dopasowują kod producentów do emulatora. Importer czyta firmware, znajduje skrypty init w obrazie boot i tworzy plan startu usług.

## 🛠️ Budowanie ze źródeł

Przetestowana wersja release aplikacji używa JDK 21 i Android SDK 36. Zobacz [instrukcję budowania i kodu źródłowego](../build-source.md) wraz ze szczegółami natywnego toolchainu i podpisywania. **Odziedziczone gotowe pliki binarne silnika nie mają jeszcze zweryfikowanego, pełnego pochodzenia kodu źródłowego i buildu**; zobacz [audyt](../license-audit.md).

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 Podziękowania

AEmulator Plus wyrósł z emulatorów HTC Desire HD i HTC One M7 [pierwotnego autora](https://t.me/istratiit_ech) — bez jego silnika tego projektu by nie było.

To zmodyfikowany fork [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), który sam jest zmodyfikowanym forkiem [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Linki

- Mój fork: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Oryginał: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Pierwotny autor: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Licencja

GPL-3.0. Android, znaki towarowe i firmware należą do ich właścicieli.

Zobacz [datowane informacje o modyfikacjach](../../NOTICE.md) oraz [otwarty audyt kodu źródłowego i licencji](../license-audit.md). Oznaczenie GPL nie jest zapewnieniem, że każdy dołączony plik binarny ma kompletny, pasujący kod źródłowy.
