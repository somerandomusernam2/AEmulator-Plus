package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Which instruction set the firmware's own programs are built for. It is NOT a new engine: the universal engine (KK)
 * is a runner and a set of daemons, and the translator it starts is the only architecture-specific host binary. An
 * x86-32 firmware runs on the same engine with libqemu_kk_i386.so instead of libqemu_kk.so.
 *
 * Added for AEmulator Plus (2026-11-04): the native arm64 engine ([Engine.N64], Android 8.0+ for x86, 5.0+ for x86-64) has
 * its own translators in analogy to libqemu_arm.so: the runner (libaemu64_<api>.so) starts [n64Lib32] for the firmware's
 * 32-bit programs and [n64Lib64] for its 64-bit ones, both stock QEMU user-mode builds (tools/qemu-zig).
 *
 * Detected at import from the ELF machine type of the firmware's own files ([detect]), not only from
 * ro.product.cpu.abi: a lot of x86 builds (Android-x86 derivatives, Intel Atom phones, emulator images) either
 * leave that property out or carry the phone's value over from a template.
 */
enum class GuestArch(
    val id: String,
    /** the host translator (a lib*.so in nativeLibraryDir) that runs the guest's programs */
    val qemuLib: String,
    /** e_machine of an ELF32 file of this architecture */
    val elfMachine: Int,
    /** suffix of the engine asset directories that hold this architecture's guest-side libraries (engines/kk-x86) */
    val assetSuffix: String,
    /** ELF class of the firmware's main side: 1 = 32-bit, 2 = 64-bit */
    val elfClass: Int = 1,
    /** native engine: the translator the runner starts for the firmware's 32-bit programs (AEMU64_QEMU) */
    val n64Lib32: String = "",
    /** native engine: the translator for the firmware's 64-bit programs (AEMU64_QEMU64); null where the firmware has none that need one */
    val n64Lib64: String? = null,
    /** oldest Android API this architecture runs on the native engine (-1: not at all) */
    val n64MinApi: Int = -1,
) {
    ARM("arm", "", 40, "", n64Lib32 = "libqemu_arm.so"),
    // x86-32 on the universal engine (libqemu_kk_i386.so, patched, up to 7.x) and, from Android 8.0 (the binder of the universal engine
    // cannot serve 8.0+), on the native engine with the stock qemu-i386
    X86("x86", "libqemu_kk_i386.so", 3, "-x86", n64Lib32 = "libqemu_i386.so", n64MinApi = 26),
    // x86-64 (Lollipop+): every program is translated, so the native engine only; the 32-bit side of a dual firmware goes to qemu-i386
    X86_64("x86_64", "libqemu_x86_64.so", 62, "-x86_64", elfClass = 2, n64Lib32 = "libqemu_i386.so", n64Lib64 = "libqemu_x86_64.so", n64MinApi = 21);

    /**
     * GUEST_NO_SMC (the app's DHD_NO_SMC) stops qemu from noticing writes into translated code and relies on the
     * guest asking for a flush with ARM's cacheflush syscall. x86 has no such call: its instruction cache is coherent
     * and a JIT just writes and jumps, so the mode would run stale code. The translator ignores the variable on x86;
     * the app never sends it either.
     */
    val supportsNoSmc: Boolean get() = this == ARM

    /** the firmware is not built for the phone's instruction set: its programs are translated, the engine's own arm64 guest files cannot be mixed in */
    val foreign: Boolean get() = this != ARM

    /** True when the native arm64 engine can run a firmware of this architecture at [api] (ARM: always, the engine picks itself) */
    fun runsOnNative(api: Int): Boolean = this == ARM || (n64MinApi in 0..api)

    /** the host translator files (names in nativeLibraryDir) the native engine needs for this architecture */
    val n64Translators: List<String> get() = listOfNotNull(n64Lib32.ifEmpty { null }, n64Lib64)

    /** the translator for an engine: the engine's own on ARM, this architecture's single one otherwise */
    fun translator(engine: Engine, xperiaPlay: Boolean = false): String = when {
        this == ARM -> if (xperiaPlay) engine.qemuXperiaPlay else engine.qemu
        else -> qemuLib
    }

    companion object {
        const val EM_386 = 3
        const val EM_ARM = 40
        const val EM_X86_64 = 62
        const val EM_AARCH64 = 183

        fun byId(id: String?): GuestArch = entries.firstOrNull { it.id == id } ?: ARM

        /** (class, machine) of an ELF file: class 1 = 32-bit, 2 = 64-bit; null when it is not an ELF file */
        fun elfInfo(f: File): Pair<Int, Int>? = runCatching {
            RandomAccessFile(f, "r").use { r ->
                val h = ByteArray(20)
                if (r.read(h) < 20) return@use null
                if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()) return@use null
                // little endian only (every architecture here is); e_machine is the 16-bit field at offset 18
                (h[4].toInt() and 0xff) to ((h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8))
            }
        }.getOrNull()

        /**
         * The 64-bit side of a firmware, looked at first: a dual firmware (x86-64 with an x86 side, arm64 with an arm side) has
         * 32-bit probes too, and its 32-bit side must not decide for it. A 32-bit-only firmware has none of these.
         */
        private val PROBES64 = listOf(
            "system/bin/app_process64", "system/bin/linker64", "system/lib64/libc.so", "system/lib64/libcutils.so",
            "system/bin/surfaceflinger", "system/bin/servicemanager",
        )

        /** Programs that are certain to be native code of the firmware (and never a script or one of the engine's own files). */
        private val PROBES = listOf(
            "system/bin/app_process", "system/bin/app_process32", "system/bin/linker", "system/bin/sh",
            "system/bin/servicemanager", "system/bin/surfaceflinger", "system/bin/toolbox", "system/bin/toybox",
            "system/lib/libc.so", "system/lib/libcutils.so", "system/lib/libdvm.so", "system/lib/libart.so",
            "init", "sbin/adbd", "system/bin/adbd", "system/bin/vold",
        )

        /** What the ELF files of an unpacked firmware say: the machine of the first probe that is an ELF file; null when none is. */
        fun machineOf(root: File): Pair<Int, Int>? {
            val top = runCatching { root.canonicalPath + File.separator }.getOrNull() ?: return null
            return (PROBES64 + PROBES).firstNotNullOfOrNull { rel ->
                val f = File(root, rel)
                // ⚠ a guest symlink such as system/bin/sh -> /system/bin/mksh is absolute: followed here it would read the
                // PHONE's file (arm64) and an x86 firmware would be taken for ARM. Links and anything that leaves the tree are skipped.
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@firstNotNullOfOrNull null
                if (runCatching { !f.canonicalPath.startsWith(top) }.getOrDefault(true)) return@firstNotNullOfOrNull null
                elfInfo(f)
            }
        }

        /**
         * The architecture of a firmware tree. The files win; ro.product.cpu.abi is the fallback for a tree that has
         * none of the probes (a partial import). x86-64 is told apart from x86-32 by the ELF class (the 64-bit side is probed first).
         */
        fun detect(root: File, abi: String): GuestArch {
            val m = machineOf(root)
            return when {
                m != null && m.first == 1 && m.second == EM_386 -> X86
                m != null && m.first == 2 && m.second == EM_X86_64 -> X86_64
                m != null -> ARM
                abi.startsWith("x86_64") -> X86_64
                abi == "x86" -> X86
                else -> ARM
            }
        }

        /** True for a firmware this build cannot run at all: MIPS, RISC-V, or an ELF of another machine than ARM / arm64 / x86-32 / x86-64 */
        fun isUnsupported(root: File, abi: String): Boolean {
            val m = machineOf(root)
            if (m != null) return !(m.first == 1 && (m.second == EM_ARM || m.second == EM_386)) &&
                !(m.first == 2 && (m.second == EM_AARCH64 || m.second == EM_X86_64))
            return abi.startsWith("mips")
        }

        /**
         * True when [f] is a guest file of [arch]'s machine (ELF32; for x86-64, ELF32 x86 as well: its 32-bit side). Used to keep another
         * architecture's helper library out of a preload.
         */
        fun isOfArch(f: File, arch: GuestArch): Boolean = elfInfo(f)?.let {
            (it.first == 1 && it.second == arch.elfMachine) || (arch == X86_64 && it.first == 1 && it.second == EM_386)
        } == true
    }
}
