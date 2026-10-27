package app.aemu.core

import java.io.File

/**
 * LD_PRELOAD of the guest programs.
 *
 * libaemushim.so is built against bionic (DT_NEEDED libc.so, undefined __errno@LIBC), so it cannot be loaded by a glibc
 * userland: Google TV firmware (Marvell Berlin, e.g. Hisense GX1200V) links its native binaries against /lib/ld-linux.so.3
 * + libc.so.6 and has no libc.so, and every service died with
 * "error while loading shared libraries: libc.so: cannot open shared object file". libashmemshim.so has no
 * dependencies and works with either.
 */
object GuestPreload {
    const val ASHMEM = "/system/lib/libashmemshim.so"
    const val SHIM = "/system/lib/libaemushim.so"
    /**
     * Makes setrlimit() report success (see shim/rlimit_shim.c): Dalvik's system_server child aborts the VM when the host
     * refuses to raise RLIMIT_MEMLOCK. No dependencies, bionic firmware only.
     */
    const val RLIMIT = "/system/lib/libaemurlimit.so"
    /**
     * Answers the extended-attribute calls (get/set xattr) that the phone's kernel refuses with ENOSYS (see xattr_shim.c):
     * the 32-bit installd of a firmware with no 64-bit side cannot make any app's data directory without them.
     * No dependencies except bionic's __errno; bionic firmware only.
     */
    const val XATTR = "/system/lib/libaemuxattr.so"

    /**
     * The same library for programs under /vendor. Their linker namespace ("(default)": default_library_paths=/vendor/lib,
     * permitted_paths=/odm:/vendor) refuses anything under /system/lib, so a preload from [XATTR] ends in
     * "CANNOT LINK EXECUTABLE ... is not accessible for the namespace" and exit code 1 before main().
     */
    const val XATTR_VENDOR = "/vendor/lib/libaemuxattr.so"

    /** the preload path of [XATTR] that the namespace of the program at [program] (a guest path) allows */
    fun xattrFor(program: String?): String =
        if (program != null && (program.startsWith("/vendor/") || program.startsWith("/odm/") ||
                program.startsWith("/system/vendor/"))) XATTR_VENDOR else XATTR

    /** glibc-only: emulates the Marvell /dev/shm_cache + /dev/shm_noncache devices (see shm_shim.c) */
    const val SHMSHIM = "/system/lib/libshmshim.so"

    /**
     * glibc dynamic loader in the tree and no bionic linker. ld-linux.so.3 is the ARM EABI loader (Marvell Berlin Google TV);
     * ld-linux.so.2 is the x86-32 one (Logitech Revue / Intel CE4100 Google TV, glibc 2.7). Modified for AEmulator Plus,
     * 2026-10-27: the x86 name was missing, so an x86 glibc firmware got none of the glibc-only handling.
     */
    fun isGlibcFirmware(root: File): Boolean =
        (File(root, "lib/ld-linux.so.3").exists() || File(root, "lib/ld-linux.so.2").exists()) &&
            !File(root, "system/bin/linker").exists()

    fun value(glibc: Boolean, xattr: Boolean = false, xattrPath: String = XATTR): String =
        if (glibc) "$ASHMEM:$SHMSHIM" else "$ASHMEM:$SHIM:$RLIMIT" + (if (xattr) ":$xattrPath" else "")
}
