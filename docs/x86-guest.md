# x86-32 guest firmware

AEmulator Plus runs an x86-32 Android firmware on the **same universal engine** as ARM firmware. The only
architecture-specific host binary is the translator: `libqemu_i386.so` (QEMU 8.2.4 `i386-linux-user` with the SENSES
patches) instead of `libqemu_kk.so`. x86-64 and the native engine are not covered.

## Importing Android-x86 ISO files

An Android-x86 live CD (`android-x86-*.iso`) is imported directly: `ramdisk.img` becomes the ramdisk and the `system.img` inside
`system.sfs` the system partition, see import-format-support.md. The result is an ordinary x86-32 image (detected from the ELF files as
below), so everything in this document applies to it. Android-x86 5.1 (API 22) is covered by the bundled software GLES renderer.

## App side

| What | Where |
| --- | --- |
| Guest architecture (`GuestArch`: `ARM`, `X86`), stored in the image as `arch` | `core/GuestArch.kt`, `core/GuestImage.kt` |
| Detection from the ELF machine type (`EM_386` = x86-32) of the firmware's own files; `ro.product.cpu.abi` is only the fallback | `GuestArch.detect`, `importer/Analyzer.kt` (profile version 17: older images are re-analysed once) |
| "Not ARM" import warning dropped for x86-32 (still shown for x86-64, MIPS, ...) | `GuestArch.isUnsupported` |
| Translator choice, no `native32`, no `DHD_NO_SMC` / `GUEST_NO_SMC`, no preload of libraries of another architecture | `core/GuestRunner.kt` |
| Translator checks with a clear message when `libqemu_i386.so` is not in the APK | `core/GuestVm.kt` |
| Engine helper files per architecture, missing files skipped | `core/EngineAssets.kt` (`forArch`), `core/TreeFixer.kt` |
| GPU/hwui off for an x86 guest until the x86 GL library exists | `core/GuestVm.kt` (`settings`) |

`ElfPatch` only touches EM_ARM files, so the firmware's own x86 libraries are left alone.

### Guest-side helper libraries (to be built for x86)

`assets/engines/common` and `assets/engines/kk` hold **ARM** binaries (their sources use ARM inline assembly in places).
For an x86 firmware the installer looks in `assets/engines/common-x86` and `assets/engines/kk-x86` under the same file
name. A file that is not there is skipped (the log says how many) and the firmware boots without it. Dex jars
(`aemu-setup.jar`, `aemu-stubs.jar`, ...) are machine independent and are taken from the ARM directories.

Build these for x86 (Android NDK, `x86`/i686) and drop them in the `-x86` directories:

* `common-x86`: `libaemushim.so` (**built**, reduced: `native/guestshim/aemushim_x86.c`, see below), `aemu_true.so` (**built**: `native/guestshim/true_x86.S`, the exit(0) stand-in for iptables/ip6tables), `libaemurlimit.so`, `libaemuxattr.so`, `libshmshim.so`, `lights.default.so`, `camera.default.so`,
  `camera.aemu_host.so`, `sensors.aemu_host.so`, `sensors.default.so`, `libcamera.none.so`
* `kk-x86`: `libashmemshim.so`, `libGLES.so`, `libGLES_split.so`, `libGLES_route.so`, `libGLES_aemu.so`,
  `gralloc.default.so`, `audio.primary.default.so` (and the audio variants), `fbpaint`

Until then an x86 firmware runs without the other shims: no ashmem/rlimit/xattr preload, no host camera and sensors, no GPU bridge.

## QEMU side (`qemu-senses/qemu`)

Tested against a QEMU 8.2.4 tree: all patches apply, `i386-linux-user` builds. Runtime fixes for an x86 guest:

* `guest_no_smc()` (`src/guest_pagelog.c`) returns 0 on non-ARM targets. GUEST_NO_SMC stops QEMU invalidating translated
  code on writes and relies on ARM's `cacheflush`; x86 has no such call, an x86 JIT would run stale code.
* Patch 0052: the ARM-only register reads (`env->regs[15]`, the `blx ip` signature) in `accel/tcg/user-exec.c` are under
  `#ifdef TARGET_ARM`.
* Patch 0056: the `sigsuspend` mask-by-value branch covers i386 as well (x86-32 takes the mask by value in the third argument).
* `src/guest_tls.c`: whole body under `#ifdef TARGET_ARM`, stub returning false elsewhere (meson.build is untouched).
* `/proc/cpuinfo`: QEMU 8.2 has none for i386, the guest would read the phone's ARM one. `src-target/i386/target_proc.h`
  replaces the stock stub when building qemu-i386 (`GUEST_CPUS`, `GUEST_X86_FLAGS`, `GUEST_X86_MODEL` tune it).
* Patch 0102 (new): crash report and stack trace for x86-32 (EIP bytes/mapping, return address from EBP, stack scan with a
  CALL-before-address filter). Patches 0035/0037 stay ARM-only.
* `build-android.sh` builds `arm-linux-user` and `i386-linux-user` in one run and writes `qemu/build/qemu-i386-android`
  (and `-dbg`) next to the ARM binary.

## Building and packaging

```
sudo ./qemu-senses/qemu/build-android.sh      # on aarch64 Linux
cp qemu-senses/qemu/build/qemu-i386-android app/src/main/jniLibs/arm64-v8a/libqemu_i386.so
```

The ARM binary goes to `libqemu_kk.so` as before. Without `libqemu_i386.so` the app still builds; an x86 image then stops
at start with "the x86 translator (libqemu_i386.so) is not in this build of the app".

## Software GL renderer for x86 firmware

x86 firmware from real devices often ships no `system/lib/egl/libGLES_android.so`; without it libEGL aborts SurfaceFlinger
("couldn't find an OpenGL ES implementation", exit 132). `core/X86SoftwareGles.kt` installs the AOSP software renderer built for
the firmware's own API level (`assets/engines/x86-gles/libGLES_android-<api>.so`, i386; 20, 21, 22, 23, 24 and 25 are bundled)
into `system/lib/egl` when the tree has none: at import (`Importer`, after analysis) and before each start
(`TreeFixer.eglConfig`, for images imported earlier). x86-32 trees only; a firmware that has its own copy is left untouched,
and an API level without a bundled file is skipped with a log line. To support another level, drop `libGLES_android-<api>.so` there.

### Android 7.x (API 24, 25): libGLES_android.so is present but ignored

7.0+ libEGL never takes `libGLES_android.so` from its directory scan. With `ro.kernel.qemu=1` it still picks that file by name,
but only when `/vendor/lib/egl` does not exist; if the directory exists (even empty) it sets `qemu.gles=2`
("Emulator has vendor provided software renderer"), scans `libGLES_*.so` skipping `libGLES_android.so`, finds nothing and
aborts (SIGABRT, exit 134). `X86SoftwareGles.ensure` therefore also installs the renderer as `system/lib/egl/libGLES_aemusw.so`
(a copy of the firmware's own `libGLES_android.so`, or the bundled one), and `TreeFixer` does not set that name aside.

## ashmem on x86 Nougat (API 24+): "Not a typewriter"

7.0+ libcutils opens `/dev/ashmem`, `fstat`s it and rejects anything that is not a character device (`errno = ENOTTY`). Under the
translator `/dev/ashmem` is a memfd, so `ashmem_create_region()` fails for ART ("ashmem_create_region failed for 'zygote space'",
then a null dereference in ART's abort handler, which looks like a zygote SIGSEGV), gralloc and MemoryHeapBase. ARM guests get
`libashmemshim.so` (LD_PRELOAD) that replaces the libcutils entry points; the x86 build is `native/guestshim/ashmem_x86.c`
(`build-x86.sh`, no libc, raw `int 0x80`), shipped as `assets/engines/kk-x86/libashmemshim.so` and installed/preloaded like the ARM one.

## x86 Nougat: what `aemushim_x86.c` fixes

`assets/engines/common-x86/libaemushim.so` is a small x86-32 port of the parts of `libaemushim.so` that 7.x needs on the phone's
kernel (no libc, raw `int 0x80`, only bionic's `__errno` is imported; the rest of `aemushim.c` is ARM code and is not ported).
Each item below is a failure seen in the logs of an API 25 x86 image (Wear, Tag Heuer) without it, in boot order:

| Symptom | Hook |
|---|---|
| zygote aborts, exit 134: `Not whitelisted : /dhd.owners`, `Unable to construct file descriptor table` (the `SIGSEGV` in `libart.so` after it is only the crash handler failing while it dumps the abort) | `readlink`, `readlinkat`, `getsockname`: the owners table is shown as `/system/framework/aemu-owners.jar` (`TreeFixer.parkNfc` links it, API 24+), pipes, the host log file and `/dev/__properties__` as `/dev/null`, the host image root is cut from paths and unix socket names. 7.0+ zygote checks every open descriptor against a whitelist of guest paths before it forks |
| `mediaextractor`/`mediacodec` exit 134: `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER): Invalid argument` | `prctl`: the guest's seccomp filter is accepted and dropped |
| installd fails every `create_app_data`, system_server dies in `SettingsProvider` (`SQLiteCantOpenDatabaseException`), zygote restarts itself (exit 137) | `get/lget/fgetxattr`, `set/lset/fsetxattr`: ENOSYS from the phone's kernel is answered with a fixed SELinux label, "not set" and successful writes; `user.default` reads as present, `user.serial` as ENODATA |
| no TCP/DNS (5.0+ netd sets `SO_MARK`) | `setsockopt`: `SO_MARK` accepted and dropped |
| AlarmManager falls back to a handler that kills system_server | `timerfd_create`: `*_ALARM` clocks become the plain ones |

Build: `native/guestshim/build-x86.sh` (needs `gcc -m32`). Test: `native/guestshim/tests/aemushim-x86-smoke.c` (header says how to run it).
An existing x86 image picks the file up on the next start (the installer compares size and CRC). `libaemuxattr.so` is not needed
on x86: its job (installd's xattr calls) is done here, in the library every guest process preloads.
