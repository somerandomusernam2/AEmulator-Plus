<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**Firmware Android klasik — HTC Sense, TouchWiz, MIUI, AOSP — di ponsel modern. Tanpa root, tanpa PC.**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · **🇮🇩 Indonesia** · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus mem-boot sistem Android 2.3–7.x asli langsung dari file firmware: ZIP recovery, arsip Odin, atau factory image Google. Kode ARM lama diterjemahkan oleh QEMU yang dimodifikasi, binder kernel diemulasikan, grafis memakai GPU ponsel, dan suara lewat sistem audio Android — semuanya di dalam aplikasi biasa.

## ✨ Fitur

- Impor hampir semua format: ZIP CWM/TWRP, Odin `.tar.md5` Samsung, factory image Google `.tgz`, `system.img`, OTA `system.new.dat.br`
- Tampilan pabrikan berjalan apa adanya: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Grafis hardware lewat jembatan GL, suara, sentuh dan multisentuh, jaringan dengan proxy TLS modern
- Folder kartu memori bersama untuk APK, musik, dan foto
- Antarmuka Material 3 Expressive dalam 18 bahasa
- Gratis dan sumber terbuka (GPL-3.0)

## 🚀 Mulai cepat

1. Unduh APK dari [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) lalu pasang.
2. Buka AEmulator Plus → **Tambah firmware** lalu pilih file. Impor butuh beberapa menit.
3. Tekan **Mulai**. Boot pertama lebih lambat: sistem mengoptimalkan aplikasi.
4. Menu ⋮ untuk volume, tombol daya, dan log; ⚙️ membuka setelan dan bahasa.

## 📋 Persyaratan

- Android 8.0+ di ponsel ARM 64-bit (arm64-v8a)
- Sekitar 1–3 GB ruang kosong per firmware
- Disarankan Snapdragon / Dimensity / Tensor terbaru

## ⚙️ Cara kerja

Setiap proses tamu berjalan di QEMU mode pengguna yang dimodifikasi. Daemon binder menggantikan driver kernel, jembatan GL meneruskan panggilan OpenGL ES ke GPU, dan pustaka tamu kecil (HAL audio, pembungkus audio policy, shim LD_PRELOAD) menyesuaikan kode pabrikan dengan emulator. Importir membaca firmware, menemukan skrip init di image boot, dan menyusun rencana mulai layanan.

## 🛠️ Membangun dari sumber

Build rilis aplikasi yang diuji memakai JDK 21 dan Android SDK 36. Lihat [petunjuk build/sumber](../build-source.md), termasuk detail toolchain native dan penandatanganan. **Binari mesin bawaan warisan belum memiliki asal-usul kode sumber/build yang terverifikasi lengkap**; lihat [audit](../license-audit.md).

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 Kredit

AEmulator Plus tumbuh dari emulator HTC Desire HD dan HTC One M7 karya [pembuat asli](https://t.me/istratiit_ech) — tanpa mesinnya proyek ini tidak akan ada.

Ini adalah fork yang dimodifikasi dari [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), yang sendiri merupakan fork yang dimodifikasi dari [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Tautan

- Fork saya: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Asli: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Pembuat: [somerandomusername2](https://github.com/somerandomusernam2)
- Pembuat asli: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Lisensi

GPL-3.0. Android, merek dagang, dan firmware milik pemiliknya.

Lihat [pemberitahuan modifikasi bertanggal](../../NOTICE.md) dan [audit sumber/lisensi yang belum selesai](../license-audit.md). Label GPL bukan sertifikasi bahwa setiap binari bawaan memiliki kode sumber yang cocok dan lengkap.
