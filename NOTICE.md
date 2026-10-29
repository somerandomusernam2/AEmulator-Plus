# AEmulator Plus modification notice

**AEmulator Plus** is a modified fork of
[drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), which is a modified fork of [uxazu/AEmulator](https://github.com/uxazu/AEmulator). It remains licensed
under the GNU General Public License, version 3; see [LICENSE](LICENSE).

The upstream project grew from the HTC Desire HD and HTC One M7 emulators by
the [original engine author](https://t.me/istratiit_ech). Those credits and the
upstream Git history are intentionally preserved.

**Modification statement:** AEmulator Plus was derived from AEmulator Sunset
`0.0.0.3-sunset.30`. Modifications by somerandomusernam2 (GitHub display name
somerandomusername2) are dated from 2026-10-04; the individual changes are recorded in this
repository's Git history. Files modified for Plus carry a dated
"Modified for AEmulator Plus" notice.

This work has been
modified by somerandomusernam2 and contributors to AEmulator Sunset. Original copyright,
license notices, credits and Git history are retained; no upstream authorship
is claimed for Plus's additions.

Source code for AEmulator Plus releases, including the exact tagged revision
used to build each APK, is published at:

<https://github.com/somerandomusernam2/AEmulator-Plus>

The Plus and Sunset application and fork-owned modifications are released under GPL-3.0.
This does not relicense independently licensed dependencies or user firmware.
See [build/source instructions](docs/build-source.md) and the
[source/licensing audit](docs/license-audit.md).

## Dated modifications

- **2026-10-04**: fork of Sunset `0.0.0.3-sunset.30`; Plus application changes
  (settings, importers, UI, helper bridges) from this date onward.
- **2026-10-11**: host microphone input. Added the host-side bridge
  (`app/src/main/java/app/aemu/core/MicBridge.kt`, `MicProtocol.kt`), the
  guest-side channel in `native/audiohal/audio_hal.c` and rebuilt the six
  `app/src/main/assets/engines/kk/audio.primary.*.so` libraries from that
  source with `native/audiohal/build-zig.sh` (LLVM/`zig cc`, no NDK). These six
  binaries are now reproducible from source tracked in this repository;
  `audio.primary.default.so` (the inherited HTC-layout HAL for Sense ROMs) is
  unchanged and still has no source mapping here.
- **2026-10-12**: merged from the AEmulator Super Plus fork (itself forked from Plus `0.0.0.2.9`;
  GPL-3.0), by manual diff against the fork point. Only these
  features were taken: European Portuguese (`pt-PT`: `app/l10n/langs/pt-PT.py`,
  `app/src/main/res/values-pt-rPT/strings.xml`, language list and `locales_config.xml`); Wi-Fi signal
  strength and nearby (locked) networks (`WifiEmu.kt`, `app/guest-wifi/**` and the rebuilt
  `aemu-wifi.jar`); configurable fake SIM cards (`SimCard.kt`, `SimSettings.kt`, `LegacySimIo.kt`, the
  SIM handling in `RilStub` in `Stubs.kt`, and the matching `VmSettings` fields and settings UI).
  Not taken: the 3D firmware library and the Android 8.x / Treble work.
- **2026-10-12** (second merge from the same fork): the VM screen theme editor. Added
  `app/src/main/java/app/aemu/core/VmTheme.kt` and `app/src/main/java/app/aemu/ui/ThemeSettings.kt`; the glyph
  sets, colours and press feedback in `HoloNavigation.kt`; the theme hooks in `VmActivity.kt`
  (bar, frame picture, rounded screen corners) and `AppSettingsActivity.kt` (the theme row replaces the navbar
  style switch); the `holoNavbar` flag in `AppPrefs.kt`; the `th_*` and `as_navbar_holo` strings
  (en, pt-BR, pt-PT). Not taken: the 3D firmware library and the other Super Plus changes.

- **2026-10-14**: raw NAND partition dumps (`mtd0.img`, `mtd1.img`, ... as found in HTC Sooner firmware). Added
  `app/src/main/java/app/aemu/importer/NandDump.kt`; `Importer.kt` (`importMtdDump`, hooks in `handle()` and the 7z
  loop); `Yaffs2Reader.kt` (the 2048+64 "sector row" spare layout); `BootImage.kt` (kernel with an appended gzip
  ramdisk, no `ANDROID!` header).

- **2026-10-23**: Android-x86 ISO images (live CD / installer `.iso`, also Bliss OS, PrimeOS ...). Added
  `app/src/main/java/app/aemu/importer/Iso9660.kt` (read-only ISO 9660 / Rock Ridge reader) and
  `app/src/test/java/app/aemu/importer/Iso9660Test.kt`; `Importer.kt` (`importIso`, the ISO probe in `handle()`); the
  `imp_iso_no_system` string; `docs/import-format-support.md`, `docs/x86-guest.md` and the README feature list.

**Unresolved source-provenance gap:** this checkout contains inherited prebuilt
engine binaries for which matching source, patches and build instructions have
not been located or verified. The tagged checkout can rebuild the APK using
those prebuilts, but is not established as complete Corresponding Source for
every bundled component. This notice is not a claim of full GPL compliance;
publishing notices or hashes does not remedy missing required source.

- **2026-10-24**: raw Spreadtrum/Unisoc partition dumps whose images are numbered in partition-table order
  (`26.system.img`, `28.vendor.img`, `25.boot.img` ...): `PartitionNames.kt` ignores the numeric prefix (the vendor
  partition was never read, so the guest lacked the firmware's own /vendor HAL services and passthrough impls) and
  `PartitionNamesTest.kt` covers it; `TreeFixer.kt` keeps a 32-bit firmware's own allocator/keymaster service instead
  of the engine's stand-in host. Binary patches of the inherited 32-bit engine hosts (no source in this tree): the
  mangled name `configureRpcThreadpool` had length prefix 20 instead of 22 (allocator host for kernels 26/27, audio and
  sensors hosts for kernel 34), and the kernel-27 plain audio host now loads `android.hardware.audio@2.0-impl.so`
  instead of the MediaTek-only `-impl-mediatek` name. Not boot-tested.
- **2026-10-24 (2)**: the engine's 32-bit allocator host also had a wrong hand-written mangled name for
  `std::string` (missing one `E` after `char_traitsIc`; fixed in `native/allocsvc/allocator_service.cpp`, the shipped
  binary cannot be patched in place). On a 32-bit firmware `TreeFixer.kt` now puts the firmware's own allocator /
  keymaster service back when an earlier start replaced it with the engine's host.
- **2026-10-24 (3)**: the one-time restart of the system after the first boot (past the setup wizard) now happens only
  when "Skip setup wizard" is switched on, and that setting is offered for every release from API 16 up (it was
  hidden above API 25).
- **2026-10-28**: the Google TV Streamer Pro (`kirkwoodpro`) boot patch of 2026-10-25 is removed (`LockSettingsBlobPatch.kt`, its
  test and `TreeFixer.lockSettingsBlobGuard()`; the dex reading helpers the Watchdog patch shares with it moved to
  `DexTools.kt`). It hid the symptom (`SyntheticPasswordCrypto.createBlob` failing in `KeyStore.setEntry`, then zygote exit 137)
  and not the cause: the 32-bit software keymaster HAL crashes inside `importKey` (exit 139) when its libraries are of two
  releases, because `libkeymaster_portable` calls virtual methods of objects `libsoftkeymasterdevice` builds and the vtable
  slots differ (the backtrace ends in `PureSoftKeymasterContext::~PureSoftKeymasterContext()` called from
  `SymmetricKeyFactory::ImportKey`). `KeymasterLibs.kt` brought missing libraries over one by one, so a firmware that has part of
  the family in `/vendor/lib` ended up with a mixture; it now makes the whole family the platform's copy (the firmware's files
  are kept in `system/.aemu-parked`) and logs where each member comes from. `TreeFixer.systemWatchdogGuard()` puts back the stock
  `services.jar` when the one in the tree was patched by the removed code.
