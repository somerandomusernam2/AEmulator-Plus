package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Added for AEmulator Plus, 2026-10-30.
 *
 * The guest's stand-in libraries (the AOSP audio policy among them) are built for a newer bionic than some firmware
 * ships. Android 4.1's libc has no `__strlen_chk` (FORTIFY_SOURCE helpers arrived with 4.2), so its linker refuses
 * the whole library: "cannot locate '__strlen_chk'". The audio-policy wrapper then returns -EINVAL ("couldn't open audio
 * policy device"), AudioService gets no policy, system_server dies with "BOOT FAILURE starting Audio Service" and
 * zygote exits with it.
 *
 * Every helper in [LIBC_FORTIFY] does the plain function's job on its leading arguments and only adds trailing size
 * arguments for the check, so a reference to it can be pointed at the plain function: the name in .dynstr is rewritten
 * in place (the plain name is always the shorter one) and the extra argument registers are simply ignored.
 */
object ElfAlias {
    /** fortify helper -> plain function with the same leading arguments */
    val LIBC_FORTIFY: Map<String, String> = mapOf(
        "__strlen_chk" to "strlen",
        "__memcpy_chk" to "memcpy",
        "__memmove_chk" to "memmove",
        "__memset_chk" to "memset",
        "__strcpy_chk" to "strcpy",
        "__strcat_chk" to "strcat",
        "__strncpy_chk" to "strncpy",
        "__strncat_chk" to "strncat",
    )

    private const val SHT_DYNAMIC = 6
    private const val SHT_DYNSYM = 11
    private const val DT_NEEDED = 1

    private class Sym(val index: Int, val nameOff: Int, val name: String, val defined: Boolean, val weak: Boolean)

    private class Elf(val d: ByteArray) {
        val b: ByteBuffer = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        var strOff = 0
        var strSize = 0
        val syms = ArrayList<Sym>()
        val needed = ArrayList<String>()
        var ok = false

        init {
            runCatching {
                if (d.size < 0x34 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4].toInt() != 1 || d[5].toInt() != 1) return@runCatching
                val shoff = b.getInt(0x20); val shent = b.getShort(0x2e).toInt() and 0xffff; val shnum = b.getShort(0x30).toInt() and 0xffff
                if (shoff <= 0 || shent < 40 || shoff.toLong() + shnum.toLong() * shent > d.size) return@runCatching
                fun sh(i: Int) = shoff + i * shent
                for (i in 0 until shnum) {
                    if (b.getInt(sh(i) + 4) != SHT_DYNSYM) continue
                    val off = b.getInt(sh(i) + 16); val size = b.getInt(sh(i) + 20); val link = b.getInt(sh(i) + 24)
                    if (link !in 0 until shnum) return@runCatching
                    strOff = b.getInt(sh(link) + 16); strSize = b.getInt(sh(link) + 20)
                    if (off < 0 || size < 0 || off.toLong() + size > d.size || strOff < 0 || strOff.toLong() + strSize > d.size) return@runCatching
                    var s = 0
                    while ((s + 1) * 16 <= size) {
                        val o = off + s * 16
                        val nameOff = b.getInt(o)
                        val info = d[o + 12].toInt() and 0xff
                        val shndx = b.getShort(o + 14).toInt() and 0xffff
                        if (nameOff in 1 until strSize) syms.add(Sym(s, nameOff, cstr(strOff + nameOff), shndx != 0, (info shr 4) == 2))
                        s++
                    }
                    break
                }
                for (i in 0 until shnum) {
                    if (b.getInt(sh(i) + 4) != SHT_DYNAMIC) continue
                    val off = b.getInt(sh(i) + 16); val size = b.getInt(sh(i) + 20)
                    var p = off
                    while (p + 8 <= off + size && p + 8 <= d.size) {
                        val tag = b.getInt(p)
                        if (tag == 0) break
                        if (tag == DT_NEEDED) needed.add(cstr(strOff + b.getInt(p + 4)))
                        p += 8
                    }
                    break
                }
                ok = syms.isNotEmpty()
            }
        }

        fun cstr(at: Int): String {
            var e = at
            while (e < d.size && d[e].toInt() != 0) e++
            return String(d, at, e - at, Charsets.US_ASCII)
        }
    }

    /** Result of [aliasMissing]: the new file and the names that were repointed. */
    class Result(val bytes: ByteArray, val renamed: List<String>)

    /**
     * [elf] with every undefined reference named in [map] that [libc] does not define repointed at its plain
     * counterpart (which [libc] must define). Null when nothing needs changing, or [elf] / [libc] cannot be read.
     */
    fun aliasMissing(elf: ByteArray, libc: ByteArray, map: Map<String, String> = LIBC_FORTIFY): Result? = runCatching {
        val target = Elf(elf)
        val c = Elf(libc)
        if (!target.ok || !c.ok) return null
        val have = c.syms.filter { it.defined }.map { it.name }.toHashSet()
        val out = elf.copyOf()
        val renamed = ArrayList<String>()
        for (s in target.syms) {
            if (s.defined) continue
            val plain = map[s.name] ?: continue
            if (s.name in have || plain !in have) continue
            // another symbol whose name string lies inside this one (shared tail) would be damaged by the rewrite
            val start = s.nameOff
            val end = s.nameOff + s.name.length
            if (target.syms.any { it !== s && it.nameOff in (start + 1) until end }) continue
            val bytes = plain.toByteArray(Charsets.US_ASCII)
            val at = target.strOff + start
            for (i in 0 until s.name.length) out[at + i] = if (i < bytes.size) bytes[i] else 0
            renamed.add("${s.name}->$plain")
        }
        if (renamed.isEmpty()) null else Result(out, renamed)
    }.getOrNull()

    /**
     * Undefined, non-weak symbols of [elf] that neither the file itself nor any library it needs (looked up in [libDir])
     * defines; a needed library that is not in [libDir] is reported as "lib:<name>". Empty when [elf] cannot be read.
     * Only for the log: it tells which symbol the next "cannot locate" would be about.
     */
    fun unresolved(elf: ByteArray, libDir: File): List<String> = runCatching {
        val t = Elf(elf)
        if (!t.ok) return emptyList()
        val defined = HashSet<String>()
        val missing = ArrayList<String>()
        for (s in t.syms) if (s.defined) defined.add(s.name)
        for (lib in t.needed) {
            val f = File(libDir, lib)
            if (!f.isFile) { missing.add("lib:$lib"); continue }
            val e = Elf(f.readBytes())
            if (e.ok) for (s in e.syms) if (s.defined) defined.add(s.name)
        }
        for (s in t.syms) if (!s.defined && !s.weak && s.name !in defined) missing.add(s.name)
        missing
    }.getOrDefault(emptyList())
}
