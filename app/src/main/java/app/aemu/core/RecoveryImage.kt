package app.aemu.core

import android.system.Os
import app.aemu.importer.BootImage
import java.io.File

/**
 * Recovery mode: the ramdisk of a recovery.img (stock, TWRP, OrangeFox, CWM…) unpacked into <image>/recovery.
 * The VM runs its /sbin/recovery under qemu with that folder as "/", sharing the system's framebuffer and touch.
 */
object RecoveryImage {
    fun dir(paths: VmPaths) = File(paths.dir, "recovery")
    fun installed(paths: VmPaths) = File(dir(paths), "sbin/recovery").isFile
    fun fb(paths: VmPaths) = File(dir(paths), "dev/graphics/fb0")

    /** Unpacks a recovery (or boot) image; false when it holds no /sbin/recovery. */
    fun install(paths: VmPaths, image: ByteArray, log: (String) -> Unit): Boolean {
        val rd = runCatching { BootImage.ramdisk(image) }.getOrNull()
        if (rd.isNullOrEmpty() || rd.none { it.name == "sbin/recovery" }) { log("recovery: image has no /sbin/recovery"); return false }
        val out = dir(paths)
        // never deleteRecursively: system/, data/, sdcard/ in it are links to the firmware's partitions and card
        ImageStore.wipe(out)
        out.mkdirs()
        val base = out.canonicalPath
        for (e in rd) {
            val n = e.name.trimStart('/')
            if (n.isEmpty() || n == ".") continue
            val f = File(out, n)
            if (!f.canonicalPath.startsWith(base)) continue
            when (e.mode and 0xF000) {
                0x4000 -> f.mkdirs()
                0xA000 -> { f.parentFile?.mkdirs(); runCatching { Os.symlink(String(e.data), f.path) } }
                0x8000 -> {
                    f.parentFile?.mkdirs(); f.writeBytes(e.data)
                    if (e.mode and 0x49 != 0) f.setExecutable(true, false)
                    f.setReadable(true, false)
                }
            }
        }
        val name = runCatching { File(out, "default.prop").readText() }.getOrDefault("")
            .let { Regex("twrp", RegexOption.IGNORE_CASE).containsMatchIn(it) }.let { if (it) "TWRP" else "" }
        log("recovery: installed ${rd.size} files ${name}".trim())
        return true
    }

    /** What [prepare] learned about the recovery's own binary, for the input service and the screen view. */
    data class Prepared(
        /** the recovery reads touch as multitouch protocol B (LG-style swipe gestures): InputService must send that */
        val touchProtocolB: Boolean = false,
        /** pixel format it draws in (0 = RGB565, 1 = RGBA/RGBX_8888, 2 = BGRA_8888) when the binary tells, else null */
        val format: Int? = null,
        /** human-readable notes for the VM log */
        val notes: List<String> = emptyList(),
    )

    /** Runtime pieces the ramdisk lacks: device nodes qemu emulates, mount points, the shared framebuffer. */
    fun prepare(paths: VmPaths, sdcard: File?, log: (String) -> Unit = {}): Prepared {
        val r = dir(paths)
        for (d in listOf("dev/graphics", "dev/input", "tmp", "cache", "data", "system", "sdcard", "proc", "sys")) File(r, d).mkdirs()
        // no tty0: minui gives up on graphics when it opens one but KDSETMODE fails; without it, it skips the step
        File(r, "dev/tty0").delete()
        // its own framebuffer (qemu only emulates an fb0 inside the guest root; links out of it are not followed
        // or not allowed), sized like the system's; the screen view shows it in recovery mode
        val fb = fb(paths)
        val size = paths.fb.length().takeIf { it > 0 } ?: 0L
        runCatching {
            if (java.nio.file.Files.isSymbolicLink(fb.toPath())) fb.delete()
            if (size > 0 && fb.length() != size) java.io.RandomAccessFile(fb, "rw").use { it.setLength(size) }
        }
        // dynamic recoveries (TWRP) get librecshim.so preloaded: mount points look mounted, reboot reaches the host
        runCatching {
            // + the system's guest shim: it runs #! scripts (qemu only starts ELF), e.g. installers inside zips
            for (lib in listOf("librecshim.so", "libaemushim.so")) {
                paths.ctx.assets.open("engines/common/$lib").use { i -> File(r, "sbin/$lib").outputStream().use { i.copyTo(it) } }
                File(r, "sbin/$lib").setReadable(true, false)
            }
        }
        File(r, "dev/aemu_power").let { if (!it.exists()) it.createNewFile() }
        runCatching { File(r, "dhd.fbgeom").writeText(File(paths.root, "dhd.fbgeom").readText()) }
        // the system's partitions and memory card, for recoveries that browse or flash files
        for ((name, target) in listOf("system" to File(paths.root, "system"), "data" to File(paths.root, "data"))) {
            val link = File(r, name)
            if (!java.nio.file.Files.isSymbolicLink(link.toPath()) && link.isDirectory && !hasFiles(link)) {
                ImageStore.wipe(link); runCatching { Os.symlink(target.absolutePath, link.path) }
            }
        }
        if (sdcard != null) {
            val link = File(r, "sdcard")
            if (link.isDirectory && link.list().isNullOrEmpty()) { link.delete(); runCatching { Os.symlink(sdcard.absolutePath, link.path) } }
        }
        return prepareStatic(r, log)
    }

    // ---- statically linked recoveries (stock AOSP / vendor ones) --------------------------------------------
    // They cannot get librecshim/libaemushim through LD_PRELOAD, so what the shims do for TWRP is done here:
    // the binary and the tree are adjusted instead.

    private fun prepareStatic(r: File, log: (String) -> Unit): Prepared {
        val bin = File(r, "sbin/recovery")
        val data = runCatching { bin.readBytes() }.getOrNull() ?: return Prepared()
        if (!isStaticArmElf(data)) return Prepared()
        val notes = ArrayList<String>()
        stubBlockDevices(r)?.let { notes += "stubbed $it block device nodes" }
        stubProcMounts(r)
        // the input FIFO cannot answer EVIOCG* ioctls; vendor recoveries that probe the device (LG: EVIOCGNAME,
        // EVIOCGBIT(EV_ABS) and EVIOCGBIT(0)) would close it and run without any input at all
        val mask = runCatching { patchEvdevProbes(bin, data) }.onFailure { log("recovery: input patch failed: ${it.message}") }.getOrDefault(0)
        if (mask != 0) notes += "input probes accepted (${Integer.toBinaryString(mask)})"
        // stock recoveries print no "Pixel format:" line (TWRP does), so read the setup out of the binary; the same
        // spot is where minui derives its row pitch, which qemu answers for 16 bpp (see patchFramebuffer)
        val fb = runCatching { patchFramebuffer(data)?.also { if (it.patched > 0) bin.writeBytes(data) } }
            .onFailure { log("recovery: framebuffer patch failed: ${it.message}") }.getOrNull()
        val format = fb?.format ?: if (fbWants32Bit(data)) 1 else null
        if (format != null) notes += when (format) { 0 -> "framebuffer RGB565"; 2 -> "framebuffer BGRA_8888"; else -> "framebuffer 32 bpp RGBX" }
        if (fb != null && fb.patched > 0) notes += "framebuffer pitch matched to the host (${fb.patched} instructions)"
        notes.forEach { log("recovery: $it") }
        return Prepared(touchProtocolB = mask and 1 != 0, format = format, notes = notes)
    }

    private fun isStaticArmElf(d: ByteArray): Boolean {
        if (d.size < 64 || d[0] != 0x7F.toByte() || d[1] != 'E'.code.toByte() || d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte()) return false
        if (d[4].toInt() != 1 || d[5].toInt() != 1) return false // ELF32, little endian
        fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (u16(18) != 40) return false // EM_ARM
        val off = u32(28); val sz = u16(42); val n = u16(44)
        for (i in 0 until n) {
            val h = off + i * sz
            if (h + 4 > d.size) return false
            if (u32(h) == 3) return false // PT_INTERP: dynamic, the preloaded shims cover it
        }
        return true
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int = 0): Int {
        var i = from
        outer@ while (i <= h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    /** a Thumb-2 "cmp r0,#0; b<cond> fail; …; b<cond> ok" pair after an input ioctl: nop the first, make the second unconditional */
    private class ProbePatch(val sig: ByteArray, val nopAt: Int, val branchAt: Int)

    private val probePatches = listOf(
        // LG KitKat recovery (G Watch…): ev_add_device — EVIOCGBIT(EV_ABS) must show ABS_MT_POSITION_X/Y (bit 0)
        ProbePatch(hex("002804dda87900f06001602904d0"), 2, 12),
        // the same family's ev_init — EVIOCGBIT(0) must show EV_KEY or EV_REL (bit 1)
        ProbePatch(hex("002803db019b13f0060f03d1"), 2, 10),
    )

    /** Bit mask of the known probes now neutralised (patched now or earlier); 0 = none of the known code is in the binary. */
    private fun patchEvdevProbes(bin: File, data: ByteArray): Int {
        var mask = 0
        var changed = false
        for ((i, p) in probePatches.withIndex()) {
            val at = indexOf(data, p.sig)
            if (at >= 0) {
                data[at + p.nopAt] = 0x00; data[at + p.nopAt + 1] = 0xBF.toByte()   // nop
                data[at + p.branchAt + 1] = 0xE0.toByte()                           // Bcc.N -> B.N, same target
                changed = true; mask = mask or (1 shl i)
            } else {
                val done = p.sig.copyOf().also {
                    it[p.nopAt] = 0x00; it[p.nopAt + 1] = 0xBF.toByte(); it[p.branchAt + 1] = 0xE0.toByte()
                }
                if (indexOf(data, done) >= 0) mask = mask or (1 shl i)
            }
        }
        if (changed) bin.writeBytes(data)
        return mask
    }

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun put16(d: ByteArray, o: Int, v: Int) { d[o] = v.toByte(); d[o + 1] = (v shr 8).toByte() }

    /**
     * @param format  pixel format minui draws in, as the host screen view numbers it (0 RGB565, 1 RGBA/RGBX, 2 BGRA)
     * @param patched instructions changed by this call (0 when nothing matched or an earlier run already did it)
     */
    internal class FbFix(val format: Int?, val patched: Int)

    /**
     * qemu answers FBIOGET_FSCREENINFO with line_length = width * 2 whatever bit depth the guest asked for (it
     * pretends to be a 16 bpp panel) and never records FBIOPUT_VSCREENINFO. TWRP divides line_length by the
     * bits_per_pixel qemu reports (16), so it lands on `width` pixels per row. AOSP's minui divides by its own
     * PIXEL_SIZE (4): its rows come out `width/2` pixels apart and its second page starts at yres*line_length
     * (half of where the host reads it), so the screen shows up as several cropped, interleaved copies.
     *
     * The right fix is to let minui see the real pitch: [hookLineLength] doubles `fi.line_length` once, right after
     * the FBIOGET_FSCREENINFO ioctl succeeded, so every later use of it is right for 32 bpp at once: the stride,
     * the second page, the size of the in-memory surface minui draws into and the size gr_flip copies to the
     * screen. Fixing only the stride and the second page is not enough: minui malloc()s `yres * line_length` bytes
     * for its drawing surface and gr_flip() copies that many, so with the halved pitch only the TOP HALF of the
     * screen was ever drawn and sent to the host (the Moto 360 recovery: bottom half unreadable).
     *
     * When the hook cannot be placed (no recognisable ioctl check, no room for it) the per-site fix is used, which
     * changes minui's gr_init in the binary, right after its FBIOGET_FSCREENINFO (movw #0x4602):
     *  - `lsrs rD, rM, #2` (stride = line_length / 4) becomes `#1`: stride = `width`
     *  - `add.w rD, rN, rOff` that follows `cmp.w rX, rOff, lsl #1` (second page = first + yres*line_length)
     *    becomes `add.w rD, rN, rOff, lsl #1`: the page starts `width*height*4` bytes in, like the host reads it
     * It also returns the pixelflinger format that gr_init stores in the surface (`strb rT, [rN, #20]`), because
     * stock recoveries do not say what they draw in: 5 is BGRA_8888 (Moto 360), 1/2 are RGBA/RGBX (LG).
     * Only Thumb-2 code is understood; null when the binary has no such setup.
     */
    internal fun patchFramebuffer(d: ByteArray): FbFix? {
        var at = 0
        var anchor = -1
        var format: Int? = null
        while (at + 4 <= d.size) {
            // movw rX, #0x4602 (FBIOGET_FSCREENINFO): F244 | imm3=6 imm8=02, any register
            if (u16(d, at) == 0xF244 && (u16(d, at + 2) and 0xF0FF) == 0x6002) {
                var found: Int? = null
                var j = at + 4
                val limit = minOf(d.size - 2, at + 0x200)
                while (j < limit && found == null) {
                    val h = u16(d, j)
                    if ((h and 0xFFC0) == 0x7500) { // strb rT, [rN, #20] = GGLSurface.format
                        val rt = h and 7
                        var k = j - 2
                        while (k >= maxOf(at, j - 24)) {
                            val m = u16(d, k)
                            if ((m and 0xF800) == 0x2000 && ((m shr 8) and 7) == rt) { found = m and 0xFF; break }
                            k -= 2
                        }
                    }
                    j += 2
                }
                if (anchor < 0 || (format == null && found != null)) { anchor = at; format = found }
                if (found != null) break
            }
            at += 2
        }
        if (anchor < 0) return null
        val host = when (format) { 4 -> 0; 1, 2 -> 1; 5 -> 2; else -> null }
        if (format == 4) return FbFix(host, 0) // 16 bpp: line_length is already right
        val end = grInitEnd(d, anchor)
        hookLineLength(d, anchor, end)?.let { return FbFix(host, it) }
        var patched = 0
        var j = anchor + 4
        while (j < end) {
            val h = u16(d, j)
            if ((h and 0xFFC0) == 0x0880) { put16(d, j, (h and 0x3F) or 0x0840); patched++ } // lsrs rD, rM, #2 -> #1
            j += 2
        }
        if (patched == 0) return FbFix(host, 0)
        j = anchor + 4
        while (j < end) {
            // cmp.w rN, rOff, lsl #1: is the double buffering test (smem_len >= 2 * yres * line_length)
            if ((u16(d, j) and 0xFFF0) == 0xEBB0 && (u16(d, j + 2) and 0xFFF0) == 0x0F40) {
                val off = u16(d, j + 2) and 0xF
                var k = j + 4
                while (k < minOf(end, j + 0x40)) {
                    val h1 = u16(d, k); val h2 = u16(d, k + 2)
                    // add.w rD, rN, rM (no shift)
                    if ((h1 and 0xFFF0) == 0xEB00 && (h2 and 0xF0F0) == 0) {
                        val rn = h1 and 0xF; val rm = h2 and 0xF
                        if (rm == off) { put16(d, k + 2, h2 or 0x40); patched++; break }
                        if (rn == off) { put16(d, k, (h1 and 0xFFF0) or rm); put16(d, k + 2, (h2 and 0xFFF0) or rn or 0x40); patched++; break }
                    }
                    k += 2
                }
                break
            }
            j += 2
        }
        return FbFix(host, patched)
    }

    /** gr_init ends at its `pop.w {…, pc}` */
    private fun grInitEnd(d: ByteArray, anchor: Int): Int {
        var end = minOf(d.size - 4, anchor + 0x180)
        var j = anchor + 4
        while (j < end) { if (u16(d, j) == 0xE8BD && (u16(d, j + 2) and 0x8000) != 0) { end = j; break }; j += 2 }
        return end
    }

    // ---- line_length hook ------------------------------------------------------------------------------------

    private const val HOOK_BYTES = 20                 // push r0 / ldr / lsl / str / pop r0
    private const val CAVE_BYTES = HOOK_BYTES + 4 + 4 // + the displaced 32-bit instruction + the branch back

    private fun u32(d: ByteArray, o: Int) = u16(d, o) or (u16(d, o + 2) shl 16)
    private fun put32(d: ByteArray, o: Int, v: Int) { put16(d, o, v and 0xFFFF); put16(d, o + 2, v ushr 16) }

    /** B.W (T4) `from` -> `to`, both as offsets in the same segment; the two halfwords packed low-first, null when out of range */
    private fun encodeBw(from: Int, to: Int): Int? {
        val off = to - (from + 4)
        if (off < -(1 shl 24) || off >= (1 shl 24) || off and 1 != 0) return null
        val s = (off shr 24) and 1
        val j1 = (if (((off shr 23) and 1) xor s == 0) 1 else 0)
        val j2 = (if (((off shr 22) and 1) xor s == 0) 1 else 0)
        val h1 = 0xF000 or (s shl 10) or ((off shr 12) and 0x3FF)
        val h2 = 0x9000 or (j1 shl 13) or (j2 shl 11) or ((off shr 1) and 0x7FF)
        return h1 or (h2 shl 16)
    }

    /** where the B.W at [at] goes, as an offset, or null when it is not one */
    private fun decodeBw(d: ByteArray, at: Int): Int? {
        val h1 = u16(d, at); val h2 = u16(d, at + 2)
        if ((h1 and 0xF800) != 0xF000 || (h2 and 0xD000) != 0x9000) return null
        val s = (h1 shr 10) and 1
        val i1 = ((h2 shr 13) and 1 xor s) xor 1
        val i2 = ((h2 shr 11) and 1 xor s) xor 1
        var off = (s shl 24) or (i1 shl 23) or (i2 shl 22) or ((h1 and 0x3FF) shl 12) or ((h2 and 0x7FF) shl 1)
        if (s == 1) off = off or (-1 shl 25)
        return at + 4 + off
    }

    /** a 32-bit Thumb-2 instruction that can be moved elsewhere unchanged: nothing PC-relative, no branch */
    private fun relocatable32(d: ByteArray, at: Int): Boolean {
        val h1 = u16(d, at); val h2 = u16(d, at + 2)
        if ((h1 and 0xF800) < 0xE800) return false                         // a 16-bit instruction
        if ((h1 and 0xF800) == 0xF000 && (h2 and 0x8000) != 0) return false // branches, bl, misc control
        if ((h1 and 0xFE00) == 0xF800 && (h1 and 0xF) == 0xF) return false  // ldr/str with Rn = pc (literal)
        if ((h1 and 0xFE50) == 0xE850 && (h1 and 0xF) == 0xF) return false  // ldrd/ldrex… literal
        if ((h1 and 0xFFF0) == 0xE8D0) return false                         // tbb/tbh/ldrex…
        if ((h1 and 0xFBFF) == 0xF2AF || (h1 and 0xFBFF) == 0xF20F) return false // adr.w
        return true
    }

    /**
     * Doubles fi.line_length right after minui's FBIOGET_FSCREENINFO succeeded (see [patchFramebuffer]); the
     * instruction that follows the ioctl's error check is moved into free space at the end of the executable
     * segment, behind a few instructions that scale the field, and replaced with a branch there:
     *
     *     push {r0}; ldr.w r0,[rFI,#0x2c]; lsl.w r0,r0,#1; str.w r0,[rFI,#0x2c]; pop {r0}   (no flags touched)
     *     <the moved instruction>; b.w back
     *
     * Recognised shape (AOSP 4.4 / Wear gr_init): `movw r1,#0x4602; …; mov r2,rFI; bl ioctl; cmp r0,#0; bge ok`
     * with rFI a callee-saved register (it is the address of `fi`). Returns the number of words changed, 0 when the
     * hook is already there, null when the binary does not look like that (the caller then falls back).
     * A binary an older version of this app patched piecewise (stride and second page) is put back first.
     */
    private fun hookLineLength(d: ByteArray, anchor: Int, end: Int): Int? {
        // the ioctl call and the register that holds &fi
        var rFi = -1
        var bl = -1
        var j = anchor + 4
        while (j < minOf(d.size - 4, anchor + 0x20)) {
            val h = u16(d, j)
            if ((h and 0xFF87) == 0x4602) rFi = (h shr 3) and 0xF                  // mov r2, rM
            val h2 = u16(d, j + 2)
            if ((h and 0xF800) == 0xF000 && ((h2 and 0xD000) == 0xD000 || (h2 and 0xD000) == 0xC000)) { bl = j; break } // bl / blx
            j += 2
        }
        if (bl < 0 || rFi !in 4..11) return null
        // cmp r0,#0 ; bge/bpl ok (the hook goes to `ok`)  |  cmp r0,#0 ; blt/bmi fail (the hook goes right behind it)
        if (bl + 8 > d.size || u16(d, bl + 4) != 0x2800) return null
        val br = u16(d, bl + 6)
        if ((br and 0xF000) != 0xD000) return null
        val cond = (br shr 8) and 0xF
        val imm = (br and 0xFF).let { if (it >= 0x80) it - 0x100 else it }
        val hook = when {
            (cond == 0xA || cond == 0x5) && imm >= 0 -> bl + 6 + 4 + 2 * imm
            (cond == 0xB || cond == 0x4) && imm > 0 -> bl + 8
            else -> return null
        }
        if (hook + 4 > d.size) return null

        // already installed? (the moved instruction is behind a branch into the cave, whose first word is ours)
        decodeBw(d, hook)?.let { cave ->
            if (cave in 0..d.size - CAVE_BYTES && u32(d, cave) == 0x0D04F84D && u32(d, cave + 4) == (0x002CF8D0 or rFi)) return 0
        }
        if (!relocatable32(d, hook)) return null

        // free space: zero padding behind the last executable segment, still inside its last page
        val cave = findCave(d, hook) ?: return null
        val back = encodeBw(cave.start + HOOK_BYTES + 4, hook + 4) ?: return null
        val there = encodeBw(hook, cave.start) ?: return null
        put32(d, cave.start, 0x0D04F84D)                            // str r0,[sp,#-4]!  (push {r0})
        put32(d, cave.start + 4, 0x002CF8D0 or rFi)                 // ldr.w r0,[rFI,#0x2c]
        put32(d, cave.start + 8, 0x0040EA4F)                        // lsl.w r0,r0,#1
        put32(d, cave.start + 12, 0x002CF8C0 or rFi)                // str.w r0,[rFI,#0x2c]
        put32(d, cave.start + 16, 0x0B04F85D)                       // ldr r0,[sp],#4  (pop {r0})
        put32(d, cave.start + HOOK_BYTES, u32(d, hook))             // the instruction the branch replaces
        put32(d, cave.start + HOOK_BYTES + 4, back)
        put32(d, hook, there)
        put32(d, cave.header + 16, cave.fileSize + cave.start + CAVE_BYTES - cave.end) // p_filesz
        put32(d, cave.header + 20, cave.memSize + cave.start + CAVE_BYTES - cave.end)  // p_memsz
        var changed = 3
        // an earlier version of this app halved the stride and doubled the second page instead: undo that, the
        // hook makes the original instructions right
        changed += undoPitchPatch(d, anchor, end)
        return changed
    }

    private class Cave(val start: Int, val end: Int, val header: Int, val fileSize: Int, val memSize: Int)

    /** zero bytes after the end of the executable PT_LOAD holding [at] that the loader maps anyway (rest of its page) */
    private fun findCave(d: ByteArray, at: Int): Cave? {
        if (d.size < 64 || u32(d, 0) != 0x464C457F || d[4].toInt() != 1 || d[5].toInt() != 1) return null
        val off = u32(d, 28); val sz = u16(d, 42); val n = u16(d, 44)
        if (sz < 32 || off < 52 || off + n * sz > d.size) return null
        for (i in 0 until n) {
            val h = off + i * sz
            if (u32(d, h) != 1 || (u32(d, h + 24) and 1) == 0) continue            // PT_LOAD, executable
            val po = u32(d, h + 4); val va = u32(d, h + 8); val fs = u32(d, h + 16); val ms = u32(d, h + 20)
            if (at < po || at + 4 > po + fs || fs != ms) continue
            val end = po + fs
            val start = (end + 3) and 3.inv()
            val pageEndVa = (va + fs + 0xFFF) and 0xFFF.inv()
            val pageEnd = po + (pageEndVa - va)                             // end of the last mapped page, as a file offset
            if (start + CAVE_BYTES > pageEnd || start + CAVE_BYTES > d.size) return null
            for (k in 0 until n) {                                          // no other segment may use that page
                val g = off + k * sz
                if (k == i || u32(d, g) != 1) continue
                val gv = u32(d, g + 8); val gm = u32(d, g + 20)
                if (gm != 0 && gv < pageEndVa && gv + gm > va + fs) return null
            }
            for (k in end until start + CAVE_BYTES) if (d[k].toInt() != 0) return null
            return Cave(start, end, h, fs, ms)
        }
        return null
    }

    /**
     * Reverts what [patchFramebuffer] used to do per site (`lsrs …,#1` strides, `add.w … lsl #1` for the second
     * page) in binaries patched by an older version; only when the second page's patched `add.w` is there, since
     * that is what tells an old patch apart from an original instruction.
     */
    private fun undoPitchPatch(d: ByteArray, anchor: Int, end: Int): Int {
        var j = anchor + 4
        var undone = 0
        while (j < end) {
            if ((u16(d, j) and 0xFFF0) == 0xEBB0 && (u16(d, j + 2) and 0xFFF0) == 0x0F40) {
                val off = u16(d, j + 2) and 0xF
                var k = j + 4
                while (k < minOf(end, j + 0x40)) {
                    val h1 = u16(d, k); val h2 = u16(d, k + 2)
                    // add.w rD, rN, rM, lsl #1 with rM = the cmp's operand: the old patch's result
                    if ((h1 and 0xFFF0) == 0xEB00 && (h2 and 0xF0F0) == 0x0040 && (h2 and 0xF) == off) {
                        put16(d, k + 2, h2 and 0x40.inv()); undone++
                        var m = anchor + 4
                        while (m < end) {
                            val h = u16(d, m)
                            if ((h and 0xFFC0) == 0x0840) { put16(d, m, (h and 0x3F) or 0x0880); undone++ }
                            m += 2
                        }
                        return undone
                    }
                    k += 2
                }
                break
            }
            j += 2
        }
        return 0
    }

    /**
     * minui's fbdev setup stores bits_per_pixel (0x20) just before FBIOPUT_VSCREENINFO (0x4601). Looks for a
     * `movw rX,#0x4601` and an immediate 0x20 in the code right before it (Thumb-2 and ARM encodings).
     */
    private fun fbWants32Bit(d: ByteArray): Boolean {
        fun b(i: Int) = d[i].toInt() and 0xFF
        for (i in 0 until d.size - 4) {
            val thumbMovw = b(i) == 0x44 && b(i + 1) == 0xF2 && b(i + 2) == 0x01 && (b(i + 3) and 0xF0) == 0x60
            val armMovw = b(i) == 0x01 && (b(i + 1) and 0x0F) == 0x06 && b(i + 2) == 0x04 && b(i + 3) == 0xE3
            if (!thumbMovw && !armMovw) continue
            for (j in maxOf(0, i - 128) until i) {
                if (b(j) == 0x20 && b(j + 1) in 0x20..0x27) return true                                   // movs rN,#0x20
                if (j + 3 < d.size && b(j) == 0x4F && b(j + 1) == 0xF0 && b(j + 2) == 0x20 && (b(j + 3) and 0xF0) == 0) return true // mov.w rN,#0x20
                if (j + 3 < d.size && b(j) == 0x20 && (b(j + 1) and 0x0F) == 0 && b(j + 2) == 0xA0 && b(j + 3) == 0xE3) return true // mov rN,#0x20
            }
        }
        return false
    }

    private class Vol(val mount: String, val fs: String, val device: String)

    /** recovery.fstab in either column order: "<device> <mount> <type>" (newer) or "<mount> <type> <device>" (older) */
    private fun volumes(r: File): List<Vol> {
        val fstab = listOf("etc/recovery.fstab", "etc/twrp.fstab").map { File(r, it) }.firstOrNull { it.isFile } ?: return emptyList()
        return fstab.readLines().mapNotNull { line ->
            val p = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }?.split(Regex("\\s+")) ?: return@mapNotNull null
            if (p.size < 3) null
            else if (p[0].startsWith("/dev/") || p[0] == "ramdisk" || p[0] == "tmpfs") Vol(p[1], p[2], p[0])
            else Vol(p[0], p[1], p[2])
        }
    }

    /**
     * Block device paths from recovery.fstab as empty files: without them the recovery polls for every
     * partition once a second (wait_for_file) and takes tens of seconds to show anything.
     */
    private fun stubBlockDevices(r: File): Int? {
        var made = 0
        for (v in volumes(r)) {
            if (!v.device.startsWith("/dev/block/")) continue
            val f = File(r, v.device.removePrefix("/"))
            if (f.exists()) continue
            runCatching { f.parentFile?.mkdirs(); f.createNewFile(); f.setReadable(true, false); f.setWritable(true, false); made++ }
        }
        return made.takeIf { it > 0 }
    }

    /** /proc/mounts for the recovery's volume scan ("E:failed to scan mounted volumes" otherwise, on screen and in the log) */
    private fun stubProcMounts(r: File) {
        val f = File(r, "proc/mounts")
        if (f.exists()) return
        val text = StringBuilder()
        for (v in volumes(r)) {
            if (v.fs in setOf("ext4", "ext3", "ext2", "f2fs", "vfat", "ramdisk") && v.mount.length > 1 && v.mount.startsWith("/"))
                text.append("${v.device} ${v.mount} ${if (v.fs == "ramdisk") "tmpfs" else v.fs} rw 0 0\n")
        }
        if (text.isNotEmpty()) runCatching { f.parentFile?.mkdirs(); f.writeText(text.toString()); f.setReadable(true, false) }
    }

    /** a ramdisk mount point may hold empty folders (TWRP: system/bin); it still gets linked to the partition */
    private fun hasFiles(d: File): Boolean = d.listFiles().orEmpty().any {
        java.nio.file.Files.isSymbolicLink(it.toPath()) || !it.isDirectory || hasFiles(it)
    }
}
