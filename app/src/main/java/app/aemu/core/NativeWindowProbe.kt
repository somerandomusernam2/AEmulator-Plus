package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Added for AEmulator Plus (2026-11-12): firmware whose `android_native_base_t` is one word longer than AOSP's.
 *
 * Measured on the Sprd GT-N9300 image (Spreadtrum MocorDroid, libui.so of 208088 bytes): the constructors of
 * FramebufferNativeWindow and of its NativeBuffer write an extra word at +0x20, right after incRef/decRef, so every window and
 * every buffer is shifted by 4:
 *
 *               AOSP (ICS..KK)    this firmware
 *   window->query     +0x54          +0x58      (+0x54 is queueBuffer there)
 *   window->perform   +0x58          +0x5c
 *   buffer->handle    +0x3c          +0x40
 *
 * The GLES bridge (assets/engines/kk/libGLES.so, installed as libGLES_bridge.so) calls `query(win, 0, &w)` through +0x54 in
 * eglCreateWindowSurface; on that firmware that is queueBuffer(win, NULL), which reads buffer->handle at +0x40 in SurfaceFlinger's
 * thread: SIGSEGV, fault address 0x40, system_server goes down and the guest boots in a loop.
 *
 * The window's `version` word (0x6c here) equals KitKat's sizeof(ANativeWindow), so it cannot tell the layouts apart. What is
 * read instead is the code: FramebufferNativeWindow::queueBuffer loads buffer->handle at a fixed displacement ([windowShift]).
 * [withWindowShift] then adds that shift to the three window-slot calls of the bridge. Anything that cannot be read with
 * confidence gives null / no change, as in [GrallocProbe]. See tools/patch_gles_bridge_mocordroid.py for the same logic.
 */
object NativeWindowProbe {
    /** buffer->handle in an AOSP android_native_buffer_t (32-bit). */
    const val AOSP_HANDLE_OFFSET = 0x3c
    private const val QUEUE_BUFFER = "_ZN7android23FramebufferNativeWindow11queueBufferE"

    /** AOSP displacements of the slots the bridge calls: query, perform. */
    private const val SLOT_QUERY = 0x54
    private const val SLOT_PERFORM = 0x58

    private class Elf(val d: ByteArray) {
        val b: ByteBuffer = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val loads = ArrayList<IntArray>()               // vaddr, file offset, filesz
        val syms = HashMap<String, IntArray>()          // name -> value, size
        fun u16(o: Int) = b.getShort(o).toInt() and 0xffff
        fun u32(o: Int) = b.getInt(o)
        fun fo(va: Int): Int? { for (l in loads) if (va >= l[0] && va < l[0] + l[2]) return va - l[0] + l[1]; return null }

        init {
            require(d.size >= 52 && d[0] == 0x7f.toByte() && d[1] == 'E'.code.toByte() && d[4].toInt() == 1 && d[5].toInt() == 1 && u16(18) == 40)
            val phoff = u32(0x1c); val phent = u16(0x2a); val phnum = u16(0x2c)
            var dynOff = -1; var dynSize = 0
            for (i in 0 until phnum) {
                val p = phoff + i * phent
                if (u32(p) == 1) loads.add(intArrayOf(u32(p + 8), u32(p + 4), u32(p + 16)))
                if (u32(p) == 2) { dynOff = u32(p + 4); dynSize = u32(p + 16) }
            }
            require(dynOff >= 0)
            val tags = HashMap<Int, Int>()
            var i = 0
            while (i + 8 <= dynSize) { val t = u32(dynOff + i); if (t == 0) break; if (!tags.containsKey(t)) tags[t] = u32(dynOff + i + 4); i += 8 }
            val symtab = fo(tags[6] ?: error("no DT_SYMTAB")) ?: error("symtab")
            val strtab = fo(tags[5] ?: error("no DT_STRTAB")) ?: error("strtab")
            // DT_HASH's nchain is the symbol count; without it the string table is taken to follow the symbols
            val nsym = tags[4]?.let { h -> fo(h)?.let { u32(it + 4) } } ?: ((strtab - symtab) / 16)
            for (s in 0 until nsym) {
                val e = symtab + s * 16
                val value = u32(e + 4)
                if (value == 0) continue
                var p = strtab + u32(e); val st = p
                while (d[p] != 0.toByte()) p++
                syms[String(d, st, p - st, Charsets.ISO_8859_1)] = intArrayOf(value, u32(e + 8))
            }
        }
    }

    /**
     * What FramebufferNativeWindow::queueBuffer of this libui uses as the displacement of buffer->handle (AOSP: 0x3c), read from
     * its Thumb-2 code: the first load through the buffer argument (r1 or a register it was copied to). Null when the function is
     * not there, is ARM code, or has no such load.
     */
    fun bufferHandleOffset(libui: ByteArray): Int? {
        try {
            val elf = Elf(libui)
            val sym = elf.syms.entries.firstOrNull { it.key.startsWith(QUEUE_BUFFER) }?.value ?: return null
            if ((sym[0] and 1) == 0) return null                    // ARM code: not handled
            val start = elf.fo(sym[0] and 1.inv()) ?: return null
            val end = minOf(start + sym[1], libui.size)
            val buf = HashSet<Int>().apply { add(1) }
            var p = start
            while (p + 2 <= end) {
                val h1 = elf.u16(p)
                val wide = (h1 shr 11) in 0x1d..0x1f && p + 4 <= end
                if (wide) {
                    val h2 = elf.u16(p + 2)
                    // ldr.w rt,[rn,#imm12] (T3)
                    if ((h1 and 0xfff0) == 0xf8d0 && (h1 and 0xf) in buf) return h2 and 0xfff
                    p += 4
                } else {
                    // mov rd,rm (T1): r1 may be copied to a callee-saved register first
                    if ((h1 and 0xff00) == 0x4600 && ((h1 shr 3) and 0xf) in buf) buf.add(((h1 shr 4) and 8) or (h1 and 7))
                    // ldr rt,[rn,#imm5*4] (T1)
                    else if ((h1 and 0xf800) == 0x6800 && ((h1 shr 3) and 7) in buf) return ((h1 shr 6) and 0x1f) * 4
                    p += 2
                }
            }
            return null
        } catch (e: RuntimeException) {
            return null
        }
    }

    /**
     * How many bytes later than AOSP this firmware lays out its native windows and buffers: 0 (AOSP layout) or 4. Null when it
     * cannot be told (callers treat that as 0: leave the bridge alone).
     */
    fun windowShift(libui: ByteArray): Int? {
        val h = bufferHandleOffset(libui) ?: return null
        return when (h - AOSP_HANDLE_OFFSET) { 0 -> 0; 4 -> 4; else -> null }
    }

    /**
     * Copy of the shipped bridge with [shift] added to the displacement of every call through a window slot: in
     * eglCreateWindowSurface query (+0x54) and perform (+0x58), in eglDestroySurface perform. Each is an ARM `ldr rt,[rn,#imm]`
     * followed by `blx rt`; exactly three such sites must be found, otherwise null (a different or already patched build). Always
     * start from the pristine asset: the patch is not idempotent.
     */
    fun withWindowShift(bridge: ByteArray, shift: Int): ByteArray? {
        if (shift == 0) return bridge
        try {
            val elf = Elf(bridge)
            val out = bridge.copyOf()
            val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            var sites = 0
            for (fn in listOf("eglCreateWindowSurface", "eglDestroySurface")) {
                val s = elf.syms[fn] ?: return null
                if ((s[0] and 1) != 0) return null                   // Thumb: not the build this was written for
                val base = elf.fo(s[0]) ?: return null
                var q = base
                while (q + 4 <= base + s[1]) {
                    val w = elf.u32(q)
                    // LDR rt,[rn,#+imm12]  (P=1, U=1, B=0, W=0, L=1)
                    if ((w and 0x0f700000) == 0x05100000 && ((w and 0xfff) == SLOT_QUERY || (w and 0xfff) == SLOT_PERFORM)) {
                        val rt = (w shr 12) and 0xf
                        var k = 1
                        while (k < 12 && q + 4 * k + 4 <= base + s[1]) {
                            val n = elf.u32(q + 4 * k)
                            if ((n and 0x0ffffff0) == 0x012fff30 && (n and 0xf) == rt) {      // blx rt
                                ob.putInt(q, (w and 0xfff.inv()) or ((w and 0xfff) + shift))
                                sites++
                                break
                            }
                            k++
                        }
                    }
                    q += 4
                }
            }
            return if (sites == 3) out else null
        } catch (e: RuntimeException) {
            return null
        }
    }
}
