package app.aemu.core

import java.io.File

/**
 * Android L preview (LPW39): /system/bin/dex2oat finishes the boot image ("dex2oat took 73s" in the log) and then
 * dies with SIGABRT inside exit(): libsigchain logs "Unable to find next sigaction in signal chain" and abort()s
 * (its dlsym(RTLD_NEXT, "sigaction") finds nothing under qemu). libart's GenerateImage() only looks at the exit
 * status, so it reports "Failed to generate image", zygote aborts (code 134) and the VM ends in FAILED, although
 * boot.art / boot.oat were completely written before exit() was called.
 *
 * dex2oat is replaced by a tiny shell wrapper around the original (dex2oat.real). It passes everything through and
 * turns "killed by SIGABRT (134)" into success only when the output it was asked to produce exists and is not empty.
 * Any other failure (a crash while compiling, so no output yet) keeps its exit status.
 * The same abort hits installd's per-app dex2oat, which passes --oat-fd instead of --oat-file; the fd is checked too.
 */
object Lpw39Dex2oat {
    const val MARKER = "# aemu-lpw39-dex2oat-wrapper"
    private const val REAL = "dex2oat.real"

    fun script(): String = listOf(
        "#!/system/bin/sh",
        MARKER,
        "/system/bin/$REAL \"\$@\"",
        "rc=\$?",
        "[ \$rc -eq 134 ] || exit \$rc",
        "img=; oat=; fd=",
        "for a in \"\$@\"; do",
        "  case \"\$a\" in",
        "    --image=*) img=\${a#--image=} ;;",
        "    --oat-file=*) oat=\${a#--oat-file=} ;;",
        "    --oat-fd=*) fd=\${a#--oat-fd=} ;;",
        "  esac",
        "done",
        "if [ -n \"\$img\" ]; then [ -s \"\$img\" ] || exit \$rc; fi",
        "if [ -n \"\$oat\" ]; then [ -s \"\$oat\" ] || exit \$rc",
        "elif [ -n \"\$fd\" ]; then [ -s \"/proc/\$\$/fd/\$fd\" ] || exit \$rc",
        "else exit \$rc; fi",
        "exit 0",
        ""
    ).joinToString("\n")

    /** True when [f] is already our wrapper. */
    fun isWrapper(f: File): Boolean =
        f.isFile && f.length() < 4096 && runCatching { f.readText(Charsets.ISO_8859_1).contains(MARKER) }.getOrDefault(false)

    /**
     * Installs the wrapper under [root]/system/bin. Returns true when something changed, false when it was already
     * in place or there is no dex2oat. Safe to call on every boot.
     */
    fun install(root: File): Boolean {
        val bin = File(root, "system/bin")
        val d2o = File(bin, "dex2oat")
        val real = File(bin, REAL)
        if (!d2o.exists() && !real.isFile) return false
        var changed = false
        if (d2o.exists() && !isWrapper(d2o)) {
            // the firmware's own binary (first run, or the image was imported again): keep it as dex2oat.real
            real.delete()
            if (!d2o.renameTo(real)) return false
            changed = true
        }
        if (!real.isFile) return false
        if (!isWrapper(d2o) || d2o.readText(Charsets.ISO_8859_1) != script()) {
            d2o.delete()
            d2o.writeText(script(), Charsets.ISO_8859_1)
            changed = true
        }
        for (f in listOf(d2o, real)) { f.setReadable(true, false); f.setExecutable(true, false) }
        return changed
    }
}
