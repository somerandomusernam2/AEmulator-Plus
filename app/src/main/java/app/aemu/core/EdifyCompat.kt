/* AEmulator Sunset addition: delta OTA support. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.util.regex.Pattern

/**
 * Rewrites the forms an Android 10/11 incremental OTA's updater-script uses into the ones [DeltaOta] already reads, so the
 * script parser needs no second grammar. Plain text in, plain text out; nothing here touches the VM.
 *
 *  - concat("a", "b", ...) of string literals becomes one literal (the E3005 / E3008 messages, and the "EMMC:" partition
 *    specs of patch_partition, are built that way);
 *  - map_partition("system") becomes "/dev/block/mapper/system", the device name [DeltaOta] already maps to a partition,
 *    so range_sha1 / block_image_verify / block_image_update / block_image_recover see a literal device;
 *  - patch_partition(src, tgt, package_extract_file(p)) of the boot partition becomes the apply_patch("EMMC:...") the
 *    boot image step reads; any other partition (recovery, modem, vbmeta, dtbo, sbl1...) is not part of a VM and
 *    becomes [SKIP_FIRMWARE], which the parser counts for its "skipped" note;
 *  - patch_partition_check(...) || abort(...) is removed: the boot image is checked against the same hashes when the
 *    install starts, before anything is changed;
 *  - ifelse(msm.boot_update("main"), ( patch_partition(...); ... ), "") is unwrapped to its statements, and the
 *    msm.boot_update("backup") / ("finalize") calls are removed.
 *
 * A script without any of those constructs is returned unchanged. Anything that is not a plain literal (concat of a
 * getprop() result, say) is left as it is, so that it is reported as a command this app cannot run.
 */
internal object EdifyCompat {
    /** Marks a firmware partition the VM does not have; the parser ignores the call and counts it. */
    const val SKIP_FIRMWARE = "aemu_skip_firmware"
    private val SKIP = Regex("""\b$SKIP_FIRMWARE\s*\(""")

    fun skippedFirmware(text: String): Int = SKIP.findAll(text).count()

    private val TRIGGERS = listOf("concat", "map_partition", "patch_partition", "boot_update")

    fun normalize(text: String): String {
        if (TRIGGERS.none { text.contains(it) }) return text
        // whole-line comments may hold quotes and brackets that would confuse the scanner; the parser drops them anyway
        var t = text.replace(Regex("(?m)^[ \\t]*#.*$"), "")
        t = foldConcat(t)
        t = mapPartitions(t)
        t = unwrapBootUpdate(t)
        t = dropBootUpdateTail(t)
        t = rewritePatchPartition(t)
        t = dropStatements(t, CHECK)
        return t
    }

    // ------------------------------------------------------------------ a tiny, string-aware scanner

    private val CONCAT = Pattern.compile("""concat\s*\(""")
    private val MAP = Pattern.compile("""map_partition\s*\(""")
    private val PATCH = Pattern.compile("""patch_partition\s*\(""")
    private val CHECK = Pattern.compile("""patch_partition_check\s*\(""")
    private val IFELSE = Pattern.compile("""ifelse\s*\(""")
    private val BOOT_UPDATE = Pattern.compile("""[A-Za-z0-9_]+\.boot_update\s*\(""")

    private class Call(val start: Int, val open: Int, val close: Int, val args: List<String>)

    /** Index just after the string literal that opens at [i]; the end of the text if it is never closed. */
    private fun endOfString(t: String, i: Int): Int {
        var j = i + 1
        while (j < t.length) {
            when (t[j]) {
                '\\' -> j += 2
                '"' -> return j + 1
                else -> j++
            }
        }
        return t.length
    }

    /** The ")" that closes the "(" at [open], or -1. */
    private fun closeParen(t: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < t.length) {
            when (t[i]) {
                '"' -> { i = endOfString(t, i); continue }
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    /** The arguments between the brackets, split at the commas that are not inside a string or a nested call. */
    private fun splitArgs(inner: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var from = 0
        var i = 0
        while (i < inner.length) {
            when (inner[i]) {
                '"' -> { i = endOfString(inner, i); continue }
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) { out.add(inner.substring(from, i).trim()); from = i + 1 }
            }
            i++
        }
        val last = inner.substring(from).trim()
        if (last.isNotEmpty() || out.isNotEmpty()) out.add(last)
        return out
    }

    /** The first call at or after [from] whose name matches [name] (a pattern that ends with the opening bracket). */
    private fun findCall(t: String, name: Pattern, from: Int): Call? {
        val m = name.matcher(t)
        var i = from
        while (i < t.length) {
            val c = t[i]
            if (c == '"') { i = endOfString(t, i); continue }
            if ((c.isLetter() || c == '_') && (i == 0 || !(t[i - 1].isLetterOrDigit() || t[i - 1] == '_' || t[i - 1] == '.'))) {
                m.region(i, t.length)
                if (m.lookingAt()) {
                    val open = m.end() - 1
                    val close = closeParen(t, open)
                    if (close < 0) return null
                    return Call(i, open, close, splitArgs(t.substring(open + 1, close)))
                }
            }
            i++
        }
        return null
    }

    private fun isLiteral(a: String) = a.length >= 2 && a[0] == '"' && endOfString(a, 0) == a.length
    private fun body(lit: String) = lit.substring(1, lit.length - 1)

    private fun skipSpace(t: String, from: Int): Int { var j = from; while (j < t.length && t[j].isWhitespace()) j++; return j }

    /** End of the statement that has its call closing at [close]: past an optional "|| abort(...)" and the ";". */
    private fun endOfStatement(t: String, close: Int, withAbort: Boolean): Int {
        var j = skipSpace(t, close + 1)
        if (withAbort && t.startsWith("||", j)) {
            var k = skipSpace(t, j + 2)
            if (t.startsWith("abort", k)) {
                k = skipSpace(t, k + 5)
                if (k < t.length && t[k] == '(') {
                    val c = closeParen(t, k)
                    if (c > 0) j = skipSpace(t, c + 1)
                }
            }
        }
        if (j < t.length && t[j] == ';') j++
        return j
    }

    // ------------------------------------------------------------------ the rewrites

    private fun foldConcat(text: String): String {
        var t = text
        var changed: Boolean
        do {
            changed = false
            var from = 0
            while (true) {
                val c = findCall(t, CONCAT, from) ?: break
                if (c.args.isNotEmpty() && c.args.all { isLiteral(it) }) {
                    val lit = "\"" + c.args.joinToString("") { body(it) } + "\""
                    t = t.substring(0, c.start) + lit + t.substring(c.close + 1)
                    from = c.start + lit.length
                    changed = true
                } else from = c.open + 1 // a nested concat inside is folded first
            }
        } while (changed)
        return t
    }

    private fun mapPartitions(text: String): String {
        var t = text
        var from = 0
        while (true) {
            val c = findCall(t, MAP, from) ?: break
            if (c.args.size == 1 && isLiteral(c.args[0])) {
                val lit = "\"/dev/block/mapper/" + body(c.args[0]) + "\""
                t = t.substring(0, c.start) + lit + t.substring(c.close + 1)
                from = c.start + lit.length
            } else from = c.open + 1
        }
        return t
    }

    /** ifelse(msm.boot_update("main"), ( patch_partition(...) || abort(...); ... ), ""); -> the statements in it. */
    private fun unwrapBootUpdate(text: String): String {
        var t = text
        var from = 0
        while (true) {
            val c = findCall(t, IFELSE, from) ?: break
            val cond = c.args.getOrNull(0)
            val then = c.args.getOrNull(1)
            val isBootUpdate = cond != null && Regex("""^[A-Za-z0-9_]+\.boot_update\s*\(\s*"[^"]*"\s*\)$""").matches(cond)
            if (isBootUpdate && then != null && then.startsWith("(") && then.endsWith(")") && then.contains("patch_partition")) {
                val statements = then.substring(1, then.length - 1)
                val end = endOfStatement(t, c.close, false)
                t = t.substring(0, c.start) + statements + "\n" + t.substring(end)
                from = c.start
            } else from = c.open + 1
        }
        return t
    }

    /** msm.boot_update("backup") / ("finalize"): the bootloader's own bookkeeping, nothing of it is in a VM. */
    private fun dropBootUpdateTail(text: String): String {
        var t = text
        var from = 0
        while (true) {
            val c = findCall(t, BOOT_UPDATE, from) ?: break
            val a = c.args.singleOrNull()
            if (a != null && isLiteral(a) && body(a) in setOf("backup", "finalize")) {
                t = t.substring(0, c.start) + t.substring(endOfStatement(t, c.close, false))
                from = c.start
            } else from = c.open + 1
        }
        return t
    }

    private val SPEC = Regex("""^"(?:EMMC|MTD):([^":]+):(\d+):([0-9a-fA-F]{40})"$""")
    private val EXTRACT = Regex("""^package_extract_file\s*\(\s*("(?:\\.|[^"\\])*")\s*\)$""")

    private fun partitionName(device: String) = device.substringAfterLast('/').removeSuffix("_a").removeSuffix("_b")

    /** patch_partition(srcSpec, tgtSpec, package_extract_file(patch)): the boot image is patched, the rest is firmware. */
    private fun rewritePatchPartition(text: String): String {
        var t = text
        var from = 0
        while (true) {
            val c = findCall(t, PATCH, from) ?: break
            val src = c.args.getOrNull(0)?.let { SPEC.matchEntire(it) }
            val tgt = c.args.getOrNull(1)?.let { SPEC.matchEntire(it) }
            val patch = c.args.getOrNull(2)?.let { EXTRACT.matchEntire(it) }
            if (c.args.size != 3 || src == null || tgt == null || patch == null || src.groupValues[1] != tgt.groupValues[1]) {
                from = c.open + 1 // not in the standard form: stays, and is reported as a command this app cannot run
                continue
            }
            val dev = src.groupValues[1]
            val repl = if (partitionName(dev) == "boot")
                "apply_patch(\"EMMC:$dev:${src.groupValues[2]}:${src.groupValues[3]}:${tgt.groupValues[2]}:${tgt.groupValues[3]}\", \"-\", " +
                    "${tgt.groupValues[3]}, ${tgt.groupValues[2]}, ${src.groupValues[3]}, package_extract_file(${patch.groupValues[1]}))"
            else "$SKIP_FIRMWARE()"
            t = t.substring(0, c.start) + repl + t.substring(c.close + 1)
            from = c.start + repl.length
        }
        return t
    }

    /** Removes every statement that is a call of [name], with the "|| abort(...)" that follows it. */
    private fun dropStatements(text: String, name: Pattern): String {
        var t = text
        var from = 0
        while (true) {
            val c = findCall(t, name, from) ?: break
            t = t.substring(0, c.start) + t.substring(endOfStatement(t, c.close, true))
            from = c.start
        }
        return t
    }
}
