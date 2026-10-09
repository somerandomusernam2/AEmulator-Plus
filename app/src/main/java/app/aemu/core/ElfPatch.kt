package app.aemu.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Точечные правки 32-битных ARM-библиотек гостя по именам экспортируемых функций.
 *
 * Нужны для проверок «настоящего железа», которые на эмуляторе проваливаются заведомо и
 * при провале роняют процесс (MediaTek DRVB) — их обходим так же, как обошёл бы их любой
 * эмулятор: функция проверки сразу возвращает «успех».
 */
object ElfPatch {
    private const val ARM_RET0 = 0xE3A00000.toInt()   // mov r0, #0
    private const val ARM_BX_LR = 0xE12FFF1E.toInt()  // bx lr
    private const val THUMB_RET0 = 0x47702000         // movs r0, #0 ; bx lr

    /** Адрес → смещение в файле, для каждой функции из списка. null — не ELF32 ARM или символа нет. */
    fun symbols(f: File, names: Set<String>): Map<String, Long>? =
        runCatching { f.readBytes() }.getOrNull()?.let { symbolsIn(it, names) }

    /** The same for the bytes of an ELF file. */
    fun symbolsIn(d: ByteArray, names: Set<String>): Map<String, Long>? {
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4] != 1.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(18).toInt() != 40) return null // EM_ARM
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        if (shoff <= 0 || shoff + shnum * shentsize > d.size) return null
        val out = HashMap<String, Long>()
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != 11) continue // SHT_DYNSYM
            val symOff = b.getInt(sh + 16); val symSize = b.getInt(sh + 20); val link = b.getInt(sh + 24)
            val strOff = b.getInt(shoff + link * shentsize + 16)
            var s = symOff
            while (s + 16 <= symOff + symSize && s + 16 <= d.size) {
                val nameOff = b.getInt(s); val value = b.getInt(s + 4).toLong() and 0xffffffffL
                val info = d[s + 12].toInt()
                if ((info and 0xf) == 2 && value != 0L) { // STT_FUNC
                    var e = strOff + nameOff
                    while (e < d.size && d[e] != 0.toByte()) e++
                    val name = String(d, strOff + nameOff, e - strOff - nameOff, Charsets.US_ASCII)
                    if (name in names) {
                        // виртуальный адрес → смещение в файле по заголовкам программы
                        val va = value and 0xfffffffeL
                        for (p in 0 until phnum) {
                            val ph = phoff + p * phentsize
                            if (b.getInt(ph) != 1) continue
                            val off = b.getInt(ph + 4).toLong() and 0xffffffffL
                            val vaddr = b.getInt(ph + 8).toLong() and 0xffffffffL
                            val filesz = b.getInt(ph + 16).toLong() and 0xffffffffL
                            if (va >= vaddr && va + 8 <= vaddr + filesz) {
                                out[name] = (off + va - vaddr) or ((value and 1L) shl 40) // бит 40 — Thumb
                            }
                        }
                    }
                }
                s += 16
            }
        }
        return out
    }

    /** The bytes returnZero() writes over a function entry: 4 for Thumb (bit 40 of [v]), 8 for ARM. */
    fun retZeroBytes(v: Long): ByteArray {
        val thumb = (v shr 40) and 1L == 1L
        val want = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            if (thumb) putInt(THUMB_RET0).putInt(THUMB_RET0) else putInt(ARM_RET0).putInt(ARM_BX_LR)
        }.array()
        return want.copyOf(if (thumb) 4 else 8)
    }

    /** Сделать функции «return 0». Возвращает число изменённых (уже исправленные не считаются). */
    fun returnZero(f: File, names: Set<String>): Int {
        val syms = symbols(f, names) ?: return 0
        var n = 0
        RandomAccessFile(f, "rw").use { raf ->
            for ((_, v) in syms) {
                val thumb = (v shr 40) and 1L == 1L
                val off = v and 0xffffffffffL
                val want = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                    if (thumb) putInt(THUMB_RET0).putInt(THUMB_RET0) else putInt(ARM_RET0).putInt(ARM_BX_LR)
                }.array()
                val have = ByteArray(if (thumb) 4 else 8)
                raf.seek(off); raf.readFully(have)
                if (have.contentEquals(want.copyOf(have.size))) continue
                raf.seek(off); raf.write(want, 0, have.size)
                n++
            }
        }
        return n
    }

    /**
     * Thumb "mov r0, r5 ; pop {r2-r6, pc}" - the common exit of SurfaceTextureClient::unlockAndPost() in the 4.0 libgui:
     * r5 holds the queueBuffer() status. Replacing the mov with "movs r0, #0" makes the call always report success.
     */
    private val THUMB_MOV_R0_R5_POP = byteArrayOf(0x28, 0x46, 0x7c, 0xbd.toByte())
    private val THUMB_MOVS_R0_0 = byteArrayOf(0x00, 0x20)

    /**
     * ICS libgui: when SurfaceTexture::queueBuffer() refuses a buffer (EINVAL, "slot N is current!"),
     * Surface.unlockCanvasAndPost() turns that into an IllegalArgumentException. In an app it only kills the app,
     * but a window drawn on a system_server thread (an app-error dialog) makes the exception fatal to the whole
     * system process, so zygote exits and everything collapses. Report success instead: the frame is dropped.
     * Only an exact, single match inside the function is patched; anything else is left alone.
     * Returns 1 when patched, 0 when already patched / not applicable.
     */
    fun unlockAndPostNeverFails(f: File): Int {
        val name = "_ZN7android20SurfaceTextureClient13unlockAndPostEv"
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val sym = symbolsIn(d, setOf(name))?.get(name) ?: return 0
        if ((sym shr 40) and 1L != 1L) return 0 // Thumb only
        val start = (sym and 0xffffffffffL).toInt()
        val size = symbolSize(d, name) ?: return 0
        if (size < 16 || start + size > d.size) return 0
        var hit = -1
        var i = start
        while (i + THUMB_MOV_R0_R5_POP.size <= start + size) {
            if (THUMB_MOV_R0_R5_POP.indices.all { d[i + it] == THUMB_MOV_R0_R5_POP[it] }) {
                if (hit >= 0) return 0 // ambiguous
                hit = i
            }
            i += 2
        }
        if (hit < 0) return 0
        RandomAccessFile(f, "rw").use { raf -> raf.seek(hit.toLong()); raf.write(THUMB_MOVS_R0_0) }
        return 1
    }

    private const val ARM_CMP_R0_0 = 0xE3500000.toInt() // cmp r0, #0
    private const val POLICY_CTOR_C1 = "_ZN7android18AudioPolicyServiceC1Ev"
    private const val POLICY_CTOR_C2 = "_ZN7android18AudioPolicyServiceC2Ev"
    private const val POLICY_FACTORY = "createAudioPolicyManager"

    /**
     * Samsung 2.x/3.x libaudioflinger: AudioPolicyService() reads ro.kernel.qemu and, when it is set, skips
     * createAudioPolicyManager() altogether ("emulator: generic policy" - a branch that was compiled out of this build).
     * mpPolicyManager stays NULL and the next statement, mpPolicyManager->setSystemProperty(), dereferences it:
     *
     *     bl   property_get            ; ro.kernel.qemu
     *     cmp  r0, #0
     *     beq  create                  ; not an emulator -> create the manager
     *     ...                          ; emulator: fall through with mpPolicyManager == NULL, crash in setSystemProperty
     *   create:
     *     add  r0, r4, #16
     *     bl   createAudioPolicyManager
     *
     * The emulator needs ro.kernel.qemu=1 for the mediaserver so that the audio HAL picks the emulator output, so the
     * property stays and the "beq" becomes an unconditional "b": the firmware's own policy library is always created.
     *
     * [words] are the ARM instructions of the constructor, the first one at virtual address [base]; [isFactory] tells
     * whether a call target is the PLT stub of createAudioPolicyManager. Returns the index of the "beq" to rewrite, or
     * null when there is no single, unambiguous match (also when it was rewritten before).
     */
    fun policyBranchIn(words: IntArray, base: Long, isFactory: (Long) -> Boolean): Int? {
        fun target(i: Int): Long {
            var off = words[i] and 0x00ffffff
            if (off and 0x00800000 != 0) off = off or 0xff000000.toInt()
            return base + 4L * i + 8 + off.toLong() * 4
        }
        var found: Int? = null
        for (i in words.indices) {
            if (words[i] ushr 24 != 0xEB) continue // bl, condition "always"
            if (!isFactory(target(i) and 0xffffffffL)) continue
            val callAt = base + 4L * i
            for (j in 1 until i) {
                if (words[j] ushr 24 != 0x0A) continue // beq
                if (words[j - 1] != ARM_CMP_R0_0) continue
                val to = target(j)
                if (to <= base + 4L * j || to < callAt - 8 || to > callAt) continue
                if (found != null && found != j) return null // ambiguous
                found = j
            }
        }
        return found
    }

    /** PLT stub address -> imported function name, for the old ARM linker layout (20-byte header, 12-byte stubs). */
    private fun pltStubs(d: ByteArray): Map<Long, String> {
        val out = HashMap<Long, String>()
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[4] != 1.toByte()) return out
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        if (shoff <= 0 || shoff + shnum * shentsize > d.size) return out
        fun rot(x: Int): Long {
            val imm = x and 0xff; val r = ((x shr 8) and 0xf) * 2
            return (Integer.rotateRight(imm, r).toLong()) and 0xffffffffL
        }
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != 9) continue // SHT_REL
            val plt = b.getInt(sh + 28) // sh_info: the section the relocations belong to
            if (plt <= 0 || plt >= shnum) continue
            val ps = shoff + plt * shentsize
            val pType = b.getInt(ps + 4); val pFlags = b.getInt(ps + 8)
            if (pType != 1 || pFlags and 4 == 0) continue // PROGBITS, executable
            val pAddr = b.getInt(ps + 12).toLong() and 0xffffffffL; val pOff = b.getInt(ps + 16); val pSize = b.getInt(ps + 20)
            val dyn = shoff + b.getInt(sh + 24) * shentsize
            val symOff = b.getInt(dyn + 16)
            val strOff = b.getInt(shoff + b.getInt(dyn + 24) * shentsize + 16)
            val got = HashMap<Long, String>()
            val relOff = b.getInt(sh + 16); val relSize = b.getInt(sh + 20)
            var r = relOff
            while (r + 8 <= relOff + relSize && r + 8 <= d.size) {
                val info = b.getInt(r + 4)
                if ((info and 0xff) == 22) { // R_ARM_JUMP_SLOT
                    val nameOff = strOff + b.getInt(symOff + (info ushr 8) * 16)
                    var e = nameOff
                    while (e < d.size && d[e] != 0.toByte()) e++
                    got[b.getInt(r).toLong() and 0xffffffffL] = String(d, nameOff, e - nameOff, Charsets.US_ASCII)
                }
                r += 8
            }
            var p = 0
            while (p + 12 <= pSize && pOff + p + 12 <= d.size) {
                val w0 = b.getInt(pOff + p); val w1 = b.getInt(pOff + p + 4); val w2 = b.getInt(pOff + p + 8)
                // add ip, pc, #x ; add ip, ip, #y ; ldr pc, [ip, #z]!
                if ((w0 and 0xfffff000.toInt()) == 0xE28FC000.toInt() && (w1 and 0xfffff000.toInt()) == 0xE28CC000.toInt() &&
                    (w2 and 0xfffff000.toInt()) == 0xE5BCF000.toInt()) {
                    val slot = (pAddr + p + 8 + rot(w0 and 0xfff) + rot(w1 and 0xfff) + (w2 and 0xfff)) and 0xffffffffL
                    got[slot]?.let { out[pAddr + p] = it }
                }
                p += 4
            }
        }
        return out
    }

    /**
     * File offsets of the "beq" that [policyBranchIn] picks in the AudioPolicyService constructors of [d] (the firmware
     * has two complete copies, C1 and C2, at different addresses); empty when nothing matches.
     */
    fun policyBranchOffsets(d: ByteArray): List<Int> {
        val names = setOf(POLICY_CTOR_C1, POLICY_CTOR_C2)
        val syms = symbolsIn(d, names) ?: return emptyList()
        val stubs = pltStubs(d)
        if (stubs.values.none { it == POLICY_FACTORY }) return emptyList()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        val hits = HashSet<Int>()
        for ((name, v) in syms) {
            if ((v shr 40) and 1L == 1L) continue // the Samsung build is ARM, not Thumb
            val start = (v and 0xffffffffffL).toInt()
            val size = symbolSize(d, name) ?: continue
            if (size < 16 || start % 4 != 0 || start + size > d.size) continue
            var base = -1L
            for (p in 0 until phnum) {
                val ph = phoff + p * phentsize
                if (b.getInt(ph) != 1) continue
                val off = b.getInt(ph + 4).toLong() and 0xffffffffL
                val vaddr = b.getInt(ph + 8).toLong() and 0xffffffffL
                val filesz = b.getInt(ph + 16).toLong() and 0xffffffffL
                if (start >= off && start < off + filesz) base = vaddr + start - off
            }
            if (base < 0) continue
            val words = IntArray(size / 4) { b.getInt(start + it * 4) }
            val at = policyBranchIn(words, base) { t -> stubs[t] == POLICY_FACTORY } ?: continue
            hits.add(start + at * 4)
        }
        return hits.sorted()
    }

    /**
     * Makes AudioPolicyService() create the firmware's audio policy even when ro.kernel.qemu is set (see
     * [policyBranchIn]). Returns 1 when patched, 0 when already patched or not applicable. Only an exact match is touched.
     */
    fun policyManagerAlways(f: File): Int {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val at = policyBranchOffsets(d)
        if (at.isEmpty()) return 0
        RandomAccessFile(f, "rw").use { raf ->
            for (o in at) { raf.seek(o + 3L); raf.write(0xEA) } // beq -> b (top byte of the LE word)
        }
        return at.size
    }

    /** st_size of the dynamic function symbol [name], or null. */
    private fun symbolSize(d: ByteArray, name: String): Int? {
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[4] != 1.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        if (shoff <= 0 || shoff + shnum * shentsize > d.size) return null
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != 11) continue
            val symOff = b.getInt(sh + 16); val symSize = b.getInt(sh + 20); val link = b.getInt(sh + 24)
            val strOff = b.getInt(shoff + link * shentsize + 16)
            var s = symOff
            while (s + 16 <= symOff + symSize && s + 16 <= d.size) {
                val nameOff = b.getInt(s)
                var e = strOff + nameOff
                while (e < d.size && d[e] != 0.toByte()) e++
                if (String(d, strOff + nameOff, e - strOff - nameOff, Charsets.US_ASCII) == name) return b.getInt(s + 8)
                s += 16
            }
        }
        return null
    }
}
