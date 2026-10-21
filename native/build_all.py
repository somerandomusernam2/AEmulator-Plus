#!/usr/bin/env python3
"""Builds the 32-bit guest components (shims, HALs, GL bridge, eglprobe) with the Android NDK's clang.

The NDK is looked for in --ndk, $ANDROID_NDK_HOME / $ANDROID_NDK_ROOT / $NDK_HOME, then in the SDK's ndk/ folder
($ANDROID_HOME, $ANDROID_SDK_ROOT, the usual per-OS locations). Hosts: Windows x64, Linux x86_64, macOS, and
Linux aarch64:
  * an aarch64 NDK build (e.g. Termux's, or lzhiyong/android-ndk-aarch64: toolchains/llvm/prebuilt/linux-aarch64)
    is used as it is;
  * Google's NDK only ships x86_64 host tools. If those cannot run here (no qemu-user / box64 binfmt), its
    sysroot, which is the same for every host, is used with the machine's own clang + lld ($CLANG overrides which
    clang). Needs `clang`, `lld` and, to strip, `llvm-strip` (llvm-objcopy) installed, e.g.
    `apt install clang lld llvm`.
"""
import argparse
import atexit
import glob
import os
import platform
import shutil
import stat
import subprocess
import sys
import tempfile

EXE = ".exe" if os.name == "nt" else ""


def host_tags():
    """Names of NDK prebuilt/ folders that were built for this host, best first."""
    machine = platform.machine().lower()
    arm = machine in ("aarch64", "arm64", "armv8l")
    if os.name == "nt":
        return ["windows-x86_64"]
    if sys.platform == "darwin":
        return (["darwin-aarch64", "darwin-arm64"] if arm else []) + ["darwin-x86_64"]
    return (["linux-aarch64", "linux-arm64"] if arm else []) + ["linux-x86_64"]


def _version_key(name):
    return [int(x) if x.isdigit() else 0 for x in name.replace("-", ".").split(".")]


def ndk_roots(cli_ndk=None):
    roots = [cli_ndk]
    for var in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK", "NDK_HOME"):
        roots.append(os.environ.get(var))
    home = os.path.expanduser("~")
    sdks = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if os.name == "nt":
        sdks.append(os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk"))
    sdks += [os.path.join(home, "Android", "Sdk"), os.path.join(home, "Library", "Android", "sdk"),
             os.path.join(home, "android-sdk"), "/opt/android-sdk", "/usr/lib/android-sdk"]
    for sdk in sdks:
        if not sdk:
            continue
        ndk_dir = os.path.join(sdk, "ndk")
        if os.path.isdir(ndk_dir):
            roots += [os.path.join(ndk_dir, d) for d in sorted(os.listdir(ndk_dir), key=_version_key, reverse=True)]
        roots.append(os.path.join(sdk, "ndk-bundle"))
    for pattern in (os.path.join(home, "android-ndk*"), "/opt/android-ndk*", "/usr/lib/android-ndk*"):
        roots += sorted(glob.glob(pattern), key=_version_key, reverse=True)
    seen, out = set(), []
    for r in roots:
        if r and os.path.isdir(r) and os.path.realpath(r) not in seen:
            seen.add(os.path.realpath(r))
            out.append(r)
    return out


def runs_here(tool):
    """True when the program can be executed on this machine (an x86_64 clang on aarch64 cannot, without binfmt)."""
    try:
        return subprocess.run([tool, "--version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=120).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def _wrapper(dir_, name, body):
    path = os.path.join(dir_, name)
    with open(path, "w") as f:
        f.write("#!/bin/sh\n" + body + "\n")
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return path


def find_toolchain(cli_ndk=None):
    """Returns {"clang", "strip", "sysroot"}: paths to a clang that runs here, a strip tool and the NDK sysroot."""
    unusable, fallback = [], None
    for ndk in ndk_roots(cli_ndk):
        base = os.path.join(ndk, "toolchains", "llvm", "prebuilt")
        if not os.path.isdir(base):
            continue
        tags = host_tags()
        dirs = [t for t in tags if os.path.isdir(os.path.join(base, t))]
        dirs += [d for d in sorted(os.listdir(base)) if d not in dirs and os.path.isdir(os.path.join(base, d))]
        for d in dirs:
            prebuilt = os.path.join(base, d)
            clang = os.path.join(prebuilt, "bin", "clang" + EXE)
            sysroot = os.path.join(prebuilt, "sysroot")
            if not os.path.isfile(clang) or not os.path.isdir(sysroot):
                continue
            if runs_here(clang):
                return {"clang": clang, "strip": os.path.join(prebuilt, "bin", "llvm-strip" + EXE),
                        "sysroot": sysroot, "ndk": ndk}
            unusable.append(clang)
            if fallback is None:
                fallback = {"ndk": ndk, "prebuilt": prebuilt, "sysroot": sysroot}
    if fallback is not None and os.name != "nt":
        return _host_clang_toolchain(fallback, unusable)
    raise RuntimeError(
        "Could not find a usable Android NDK. Pass --ndk <dir> or set ANDROID_NDK_HOME"
        + ("; on aarch64 Linux either use an aarch64 NDK build (toolchains/llvm/prebuilt/linux-aarch64), "
           "or install clang + lld and point at Google's NDK for its sysroot." if platform.machine().lower()
           in ("aarch64", "arm64") else "."))


def _host_clang_toolchain(fb, unusable):
    clang = os.environ.get("CLANG") or shutil.which("clang")
    lld = shutil.which("ld.lld") or shutil.which("lld")
    if not clang or not lld:
        missing = " and ".join(n for n, v in (("clang", clang), ("lld", lld)) if not v)
        raise RuntimeError(
            "The NDK at %s only has host tools that cannot run on %s/%s (%s), and this machine has no %s.\n"
            "Install them (Debian/Ubuntu: apt install clang lld llvm), use an aarch64 NDK build, or enable "
            "qemu-user/box64 binfmt so the NDK's x86_64 clang can run." % (
                fb["ndk"], platform.system(), platform.machine(), unusable[0], missing))
    tmp = tempfile.mkdtemp(prefix="aemu-ndk-")
    atexit.register(shutil.rmtree, tmp, ignore_errors=True)
    flags = ['--sysroot="%s"' % fb["sysroot"], "-fuse-ld=lld"]
    # compiler-rt's Android builtins and the compiler's own headers come from the NDK too, when it ships them
    for rd in sorted(glob.glob(os.path.join(fb["prebuilt"], "lib", "clang", "*"))):
        if os.path.isfile(os.path.join(rd, "lib", "linux", "libclang_rt.builtins-arm-android.a")):
            flags.append('-resource-dir="%s"' % rd)
            break
    wrapped = _wrapper(tmp, "clang", 'exec "%s" %s "$@"' % (clang, " ".join(flags)))
    strip = shutil.which("llvm-strip")
    if strip is None:
        objcopy = shutil.which("llvm-objcopy")
        body = ('exec "%s" --strip-all "$1"' % objcopy) if objcopy else \
            'echo "warning: no llvm-strip / llvm-objcopy, $1 left unstripped" >&2; exit 0'
        strip = _wrapper(tmp, "strip", body)
    print("NDK host tools cannot run here: using %s with the sysroot of %s" % (clang, fb["ndk"]))
    return {"clang": wrapped, "strip": strip, "sysroot": fb["sysroot"], "ndk": fb["ndk"]}


def run(cmd, cwd=None):
    print(" ".join(cmd))
    res = subprocess.run(cmd, cwd=cwd)
    if res.returncode != 0:
        sys.exit(f"Failed with code {res.returncode}: {' '.join(cmd)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--ndk", help="Android NDK directory (default: $ANDROID_NDK_HOME, then the SDK's ndk/ folder)")
    args = ap.parse_args()
    try:
        tc = find_toolchain(args.ndk)
    except RuntimeError as e:
        sys.exit(str(e))
    clang, strip, sysroot = tc["clang"], tc["strip"], tc["sysroot"]
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets = os.path.join(repo_root, "app", "src", "main", "assets", "engines")
    arm_lib = os.path.join(sysroot, "usr", "lib", "arm-linux-androideabi", "21")

    print(f"Using NDK Clang: {clang}")

    # 1. guestshim: libaemushim.so, aemu_true.so, librecshim.so
    shim_dir = os.path.join(repo_root, "native", "guestshim")
    common_dir = os.path.join(assets, "common")
    os.makedirs(common_dir, exist_ok=True)
    
    out_shim = os.path.join(common_dir, "libaemushim.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-fvisibility=hidden", "-fno-stack-protector", "-ffreestanding", "-fno-builtin",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--no-undefined", "-Wl,-soname,libaemushim.so",
         "-Wl,--build-id=none", "-o", out_shim, "aemushim.c", "crash.c", "-lc"], cwd=shim_dir)
    subprocess.run([strip, out_shim])

    out_true = os.path.join(common_dir, "aemu_true.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-nostdlib", "-static", "-Wl,--build-id=none",
         "-o", out_true, "true.S"], cwd=shim_dir)
    subprocess.run([strip, out_true])

    out_rec = os.path.join(common_dir, "librecshim.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-fvisibility=hidden", "-fno-stack-protector", "-ffreestanding", "-fno-builtin",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--no-undefined", "-Wl,-soname,librecshim.so",
         "-Wl,--build-id=none", "-o", out_rec, "recshim.c", "-lc"], cwd=shim_dir)
    subprocess.run([strip, out_rec])

    # ashmem: libashmemshim.so for GB and KK
    gb_dir = os.path.join(assets, "gb")
    kk_dir = os.path.join(assets, "kk")
    os.makedirs(gb_dir, exist_ok=True)
    os.makedirs(kk_dir, exist_ok=True)
    out_ashmem_kk = os.path.join(kk_dir, "libashmemshim.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-fvisibility=hidden", "-fno-stack-protector", "-ffreestanding", "-fno-builtin",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--no-undefined", "-Wl,-soname,libashmemshim.so",
         "-Wl,--build-id=none", "-o", out_ashmem_kk, "ashmem.c", "-lc"], cwd=shim_dir)
    subprocess.run([strip, out_ashmem_kk])
    import shutil
    shutil.copy2(out_ashmem_kk, os.path.join(gb_dir, "libashmemshim.so"))

    # 2. lightshal: lights.default.so
    lights_dir = os.path.join(repo_root, "native", "lightshal")
    out_lights = os.path.join(common_dir, "lights.default.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,--no-undefined",
         "-Wl,-soname,lights.default.so", "-o", out_lights, "lights_hal.c", "-lc"], cwd=lights_dir)
    subprocess.run([strip, out_lights])

    # 3. sensorshal: sensors.default.so
    sensors_dir = os.path.join(repo_root, "native", "sensorshal")
    out_sensors = os.path.join(common_dir, "sensors.default.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,--no-undefined",
         "-Wl,-soname,sensors.default.so", "-o", out_sensors, "sensors_hal.c", "-lc"], cwd=sensors_dir)
    subprocess.run([strip, out_sensors])

    # 4. camerahal: camera.default.so, libcamera.none.so
    camera_dir = os.path.join(repo_root, "native", "camerahal-n64")
    out_camera = os.path.join(common_dir, "camera.default.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,--no-undefined",
         "-Wl,-soname,camera.default.so", "-o", out_camera, "camera_hal.c", "-ldl", "-lc"], cwd=camera_dir)
    subprocess.run([strip, out_camera])

    out_cam_none = os.path.join(common_dir, "libcamera.none.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,--no-undefined",
         "-Wl,-soname,libcamera.so", "-o", out_cam_none, "camera_gb_none.c"], cwd=camera_dir)
    subprocess.run([strip, out_cam_none])

    # 5. audiohal: audio.primary.*.so
    audio_dir = os.path.join(repo_root, "native", "audiohal")
    kk_dir = os.path.join(assets, "kk")
    os.makedirs(kk_dir, exist_ok=True)

    out_aosp = os.path.join(kk_dir, "audio.primary.aosp.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-mfloat-abi=softfp", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,-soname,audio.primary.default.so",
         "-o", out_aosp, "audio_hal.c", "-lc"], cwd=audio_dir)
    subprocess.run([strip, out_aosp])

    out_ics = os.path.join(kk_dir, "audio.primary.ics.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-mfloat-abi=softfp", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector", "-DAEMU_ICS",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,-soname,audio.primary.default.so",
         "-o", out_ics, "audio_hal.c", "-lc"], cwd=audio_dir)
    subprocess.run([strip, out_ics])

    out_ics_qcom = os.path.join(kk_dir, "audio.primary.ics-qcom.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-mfloat-abi=softfp", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector", "-DAEMU_ICS", "-DAEMU_QCOM",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,-soname,audio.primary.default.so",
         "-o", out_ics_qcom, "audio_hal.c", "-lc"], cwd=audio_dir)
    subprocess.run([strip, out_ics_qcom])

    out_mtk = os.path.join(kk_dir, "audio.primary.mtk.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-mfloat-abi=softfp", "-Os", "-fPIC", "-shared",
         "-nostdlib", "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector", "-DAEMU_MTK", '-DAEMU_PCM_DEV="/dev/aemu_pcm"',
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,-soname,libaudio.primary.default.so",
         "-o", out_mtk, "audio_hal.c", "-lc"], cwd=audio_dir)
    subprocess.run([strip, out_mtk])

    # 6. apwrap: audio_policy.wrap.so
    ap_dir = os.path.join(repo_root, "native", "apwrap")
    out_apwrap = os.path.join(kk_dir, "audio_policy.wrap.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-mthumb", "-Os", "-fPIC", "-shared", "-nostdlib",
         "-ffreestanding", "-fno-builtin", "-fvisibility=hidden", "-fno-stack-protector",
         "-Wl,--hash-style=sysv", "-Wl,-z,norelro", "-Wl,--build-id=none", "-Wl,-soname,audio_policy.default.so",
         "-o", out_apwrap, "apwrap.c", "tramp.S", "-ldl", "-lc", "-llog"], cwd=ap_dir)
    subprocess.run([strip, out_apwrap])

    # 7. glsplit: libGLES_split.so
    glsplit_dir = os.path.join(repo_root, "native", "glsplit")
    out_split = os.path.join(kk_dir, "libGLES_split.so")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-Os", "-fPIC", "-shared", "-nostdlib",
         "-fvisibility=hidden", "-fno-stack-protector", "-ffreestanding", "-fno-builtin", "-Wl,--hash-style=sysv", "-Wl,-z,norelro",
         "-Wl,-soname,libGLES_split.so", "-Wl,--build-id=none", "-o", out_split, "split.c", "stubs.S",
         f"-L{arm_lib}", "-ldl", "-lc", "-llog"], cwd=glsplit_dir)
    subprocess.run([strip, out_split])

    # 8. eglprobe: guest diagnostic (what the guest's EGL / SwiftShader accepts). A PIE executable, not a library:
    #    installed by TreeFixer.installEglProbe on 32-bit-only firmware.
    probe_dir = os.path.join(repo_root, "native", "eglprobe")
    out_probe = os.path.join(common_dir, "eglprobe")
    run([clang, "--target=armv7a-linux-androideabi21", "-march=armv7-a", "-Os", "-fPIE", "-pie",
         "-fno-stack-protector", "-Wl,--build-id=none", "-o", out_probe, "eglprobe.c", "-ldl"], cwd=probe_dir)
    subprocess.run([strip, out_probe])

    print("=== All native guest components compiled successfully! ===")

if __name__ == "__main__":
    main()
