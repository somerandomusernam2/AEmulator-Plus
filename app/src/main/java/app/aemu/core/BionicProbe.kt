package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Tells from the firmware's own files whether it carries the Android 6.0 userland, whatever ro.build.version.sdk says.
 *
 * Marshmallow previews (the Nexus 6 "MPZ44Q" build, for one) still report API 22 but already ship the 6.0 bionic:
 * every program starts with personality(PER_LINUX32) and exits with code 1 when that fails ("error setting PER_LINUX32
 * personality: Invalid argument"). The API number is the wrong thing to ask, so the rules that belong to the 6.0
 * userland (the personality stub, XML settings, vold's volume events, the zygote owners alias) ask this instead:
 * the check reads the marker string out of the linker (and libc.so), which carry it exactly in the bionic that needs those rules.
 */
object BionicProbe {
    private val MARKER = "error setting PER_LINUX32 personality".toByteArray(Charsets.US_ASCII)

    /** Does this file hold the 6.0+ bionic start-up check? Reads the file in chunks; no whole-file copy. */
    fun hasPersonalityCheck(f: File): Boolean {
        if (!f.isFile || f.length() < MARKER.size) return false
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val buf = ByteArray(256 * 1024)
                val keep = MARKER.size - 1
                var have = 0
                var found = false
                while (!found) {
                    val n = raf.read(buf, have, buf.size - have)
                    if (n <= 0) break
                    val end = have + n
                    if (indexOf(buf, end) >= 0) { found = true; break }
                    // keep a tail so a marker split across two reads is still found
                    val tail = minOf(keep, end)
                    System.arraycopy(buf, end - tail, buf, 0, tail)
                    have = tail
                }
                found
            }
        }.getOrDefault(false)
    }

    private fun indexOf(b: ByteArray, end: Int): Int {
        val m = MARKER
        var i = 0
        while (i + m.size <= end) {
            var j = 0
            while (j < m.size && b[i + j] == m[j]) j++
            if (j == m.size) return i
            i++
        }
        return -1
    }

    /** 6.0+ userland: the API number says so, or the firmware's libc does (a preview that still reports 22). */
    fun isMarshmallowPlus(root: File, api: Int): Boolean =
        api >= 23 || carriesCheck(root)

    /** The check is not in libc.so: 6.0 puts it in the linker, which is linked statically against its own libc. */
    private fun carriesCheck(root: File): Boolean =
        listOf("system/bin/linker", "system/lib/libc.so", "system/bin/linker64", "system/lib64/libc.so")
            .any { hasPersonalityCheck(File(root, it)) }

    /** The API to apply the 6.0 rules by: the real one, raised to 23 when the tree is 6.0 inside. Never lowers it. */
    fun ruleApi(root: File, api: Int): Int = if (api < 23 && isMarshmallowPlus(root, api)) 23 else api
}
