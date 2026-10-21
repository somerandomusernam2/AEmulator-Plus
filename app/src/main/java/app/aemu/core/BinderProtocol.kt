package app.aemu.core

import java.io.File

/**
 * The binder protocol version the guest's libbinder insists on. A kernel driver answers BINDER_VERSION with one number
 * and libbinder aborts when it is not the one it was built for: 32-bit firmware built with BINDER_IPC_32BIT wants 7,
 * most others want 8. Which one a firmware needs cannot be read reliably from its files, so it is taken from what the
 * guest itself reports when it refuses ("kernel driver version (8) differs from user space version (7)", or the
 * hw-ProcessState form "Binder driver protocol(8) does not match user space protocol(7)!"), remembered in
 * run/binder.protocol and handed to the binder daemons (env [ENV]) on the next start.
 */
object BinderProtocol {
    const val ENV = "AEMU_BINDER_PROTOCOL"
    /** read by the guest runner (run64.c in each native/guest64/<api> folder): protocol 7 turns on the 32-bit binder front end for qemu-arm programs */
    const val GUEST_ENV = "AEMU64_BINDER_PROTOCOL"
    private const val FILE = "binder.protocol"
    private val MISMATCH = listOf(
        Regex("""kernel driver version \((\d+)\) differs from user space version \((\d+)\)"""),
        Regex("""[Bb]inder driver protocol\s*\((\d+)\) does not match user space protocol\s*\((\d+)\)"""),
    )

    /** the (driver, wanted) pair from a service's output, or null when it holds no such complaint */
    fun parseMismatch(text: String): Pair<Int, Int>? {
        for (re in MISMATCH) {
            val m = re.findAll(text).lastOrNull() ?: continue
            val driver = m.groupValues[1].toIntOrNull() ?: continue
            val wanted = m.groupValues[2].toIntOrNull() ?: continue
            if (wanted != driver && wanted in 1..32) return driver to wanted
        }
        return null
    }

    /** the remembered version, or null while the daemons run with their own default */
    fun saved(bin: File): Int? = runCatching { File(bin, FILE).readText().trim().toIntOrNull() }.getOrNull()?.takeIf { it in 1..32 }

    fun save(bin: File, version: Int) { runCatching { File(bin, FILE).writeText(version.toString()) } }

    fun forget(bin: File) { runCatching { File(bin, FILE).delete() } }
}
