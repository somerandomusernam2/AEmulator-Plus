/* AEmulator Plus addition, 2026-10-28. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Lets libart accept a boot image whose oat says "concurrent-copying=false" while the runtime uses CC (see [BootImageGc]).
 *
 * In the 32-bit ARM libart of Android 14 (Wear OS 4, build AW2A.240903.001) the check that prints
 * "ValidateOatFile found read barrier state mismatch" is, in Thumb-2:
 *
 *     ldr  r1, [pc, #..] ; add r1, pc ; ldr r1, [r1]   // r1 = &gUseReadBarrier
 *     ldrb r4, [r1]                                    // the runtime's state
 *     cmp  r4, r0                                      // r0 = the oat header's "concurrent-copying"
 *     bne.w <error path>                               // <- turned into nop.w
 *
 * The bytes `09 68 0c 78 84 42 40 f0 xx xx` are matched (exactly one place in that library); only the six bytes of the
 * `ldr r1,[r1] ; ldrb r4,[r1] ; cmp r4,r0` before the branch are required to be the same, the branch offset is free. The
 * library is changed in place; run on every boot, a library that is already patched, or another build, is left alone.
 */
object LibArtReadBarrierPatch {
    // ldr r1,[r1] ; ldrb r4,[r1] ; cmp r4,r0 ; then the first halfword of `bne.w` (F040 / F440: condition NE, either sign)
    private val HEAD = byteArrayOf(0x09, 0x68, 0x0c, 0x78, 0x84.toByte(), 0x42)
    private val NOP_W = byteArrayOf(0xAF.toByte(), 0xF3.toByte(), 0x00, 0x80.toByte()) // F3AF 8000

    /** Offset of the `bne.w` in [d], or -1 when there is not exactly one such place. */
    fun findBranch(d: ByteArray): Int {
        var found = -1
        var i = 0
        while (i + HEAD.size + 4 <= d.size) {
            if (d[i] == HEAD[0] && (HEAD.indices).all { d[i + it] == HEAD[it] }) {
                val b = i + HEAD.size
                if (d[b] == 0x40.toByte() && (d[b + 1] == 0xF0.toByte() || d[b + 1] == 0xF4.toByte())) {
                    if (found >= 0) return -1
                    found = b
                }
            }
            i++
        }
        return found
    }

    /** True when [d] has the check already turned off (the same lead-in followed by our nop.w). */
    fun isPatched(d: ByteArray): Boolean {
        var i = 0
        while (i + HEAD.size + 4 <= d.size) {
            if (d[i] == HEAD[0] && (HEAD.indices).all { d[i + it] == HEAD[it] } &&
                (NOP_W.indices).all { d[i + HEAD.size + it] == NOP_W[it] }) return true
            i++
        }
        return false
    }

    /** [d] patched in place; false when the check is not found (or not unique). */
    fun patchBytes(d: ByteArray): Boolean {
        val b = findBranch(d)
        if (b < 0) return false
        NOP_W.copyInto(d, b)
        return true
    }

    /** The libart copies of the ART module in [root]; the 32-bit one is the guest's here. */
    private fun libs(root: File) = listOf("apex/com.android.art/lib/libart.so", "system/lib/libart.so").map { File(root, it) }.filter { it.isFile }

    /** Patches the guest's libart; returns the number of libraries changed. Safe to call on every boot. */
    fun install(root: File): Int {
        var n = 0
        for (f in libs(root)) runCatching {
            val d = f.readBytes()
            if (d.size < 1 shl 20 || d[0] != 0x7f.toByte() || isPatched(d)) return@runCatching
            val b = findBranch(d)
            if (b < 0) return@runCatching
            RandomAccessFile(f, "rw").use { it.seek(b.toLong()); it.write(NOP_W) }
            n++
        }
        return n
    }

    /** [opts] (the value of dalvik.vm.extra-opts, may be null) with ART's "-Xint" added once; the other options stay. */
    fun withInterpretOnly(opts: String?): String {
        val parts = opts.orEmpty().split(' ').filter { it.isNotEmpty() }
        return if ("-Xint" in parts) parts.joinToString(" ") else (parts + "-Xint").joinToString(" ")
    }

    /** True when some libart of the tree has the check off. */
    fun isInstalled(root: File): Boolean = libs(root).any { f -> runCatching { isPatched(f.readBytes()) }.getOrDefault(false) }
}
