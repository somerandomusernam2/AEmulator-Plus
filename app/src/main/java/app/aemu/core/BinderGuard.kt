package app.aemu.core

import java.io.File
import java.security.MessageDigest

/**
 * Containment of the binder daemon's "bare object" fault, armed per VM.
 *
 * libbinderd_kk (no source) now and then hands a process an object of type BINDER_TYPE_BINDER that belongs to the
 * sender; libbinder uses its cookie as a local pointer and the process hangs inside Parcel.readStrongBinder, until every
 * binder thread of system_server is stuck (Sprd/MocorDroid 4.0.3 lock screen: LockScreen.getMissCallCount). The daemon
 * reports the fault itself and later the hung transaction. libaemubinderguard.so (native/guestshim/binderguard.c)
 * clears such objects in the guest; this object decides when it is used. Nothing is preloaded until a VM has shown
 * the fault, the same way [BinderProtocol] learns its version: the evidence is read from run/binderd.log, the decision
 * is remembered in run/binder.guard and handed to the guest on the next start.
 *
 * The record names the daemon build it was learned with (size + SHA-1 of the binder daemon library): a different
 * daemon is a different log format and a different set of faults, so it starts over from "not armed".
 * A guard that was armed and never fired while the hang came back is marked ineffective and is not armed again for that
 * daemon build: the cause is then somewhere else and the guard only adds risk.
 */
object BinderGuard {
    const val ENV = "AEMU_GUARD"
    const val ENV_LOG = "AEMU_GUARD_LOG"
    const val LIB = "/system/lib/libaemubinderguard.so"
    private const val FILE = "binder.guard"
    const val LOG = "binderguard.log"

    /** the daemon's own fault report (its log is in Russian); both must appear in one run of the daemon */
    private val BARE_REPLY = "ОТВЕТ ГОЛЫМ ОБЪЕКТОМ"
    private val HUNG = "ЗАВИСШАЯ ТРАНЗАКЦИЯ"
    /** the counter line of the pool report: "ответов голым объектом 19" */
    private val BARE_COUNT = Regex("""ответов голым объектом\s+(\d+)""")

    data class State(val armed: Boolean, val ineffective: Boolean, val binderd: String, val since: String, val bare: Int)

    /** true when [binderdRun] (the log of ONE run of the daemon) holds a bare-object reply and a hung transaction */
    fun detect(binderdRun: String): Boolean = binderdRun.contains(BARE_REPLY) && binderdRun.contains(HUNG)

    /** the daemon's own count of bare replies in that run (0 when it printed no pool report) */
    fun bareCount(binderdRun: String): Int = BARE_COUNT.findAll(binderdRun).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0

    fun state(bin: File): State? = runCatching {
        val m = File(bin, FILE).readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        State(m["armed"] == "1", m["ineffective"] == "1", m["binderd"].orEmpty(), m["since"].orEmpty(), m["bare"]?.toIntOrNull() ?: 0)
    }.getOrNull()

    private fun write(bin: File, s: State) {
        runCatching { File(bin, FILE).writeText("armed=${if (s.armed) 1 else 0}\nineffective=${if (s.ineffective) 1 else 0}\nbinderd=${s.binderd}\nsince=${s.since}\nbare=${s.bare}\n") }
    }

    /** true when the guard goes into the guest at the next start: armed for this very daemon build, not found useless */
    fun armed(bin: File, binderdId: String): Boolean = state(bin)?.let { it.armed && !it.ineffective && it.binderd == binderdId } == true

    /** the record applies to this daemon build (a record of another build is stale) */
    fun known(bin: File, binderdId: String): State? = state(bin)?.takeIf { it.binderd == binderdId }

    fun arm(bin: File, binderdId: String, since: String, bare: Int) = write(bin, State(true, false, binderdId, since, bare))

    fun markIneffective(bin: File, binderdId: String, since: String, bare: Int) = write(bin, State(false, true, binderdId, since, bare))

    fun forget(bin: File) { runCatching { File(bin, FILE).delete() }; runCatching { File(bin, LOG).delete() } }

    /** lines the guard wrote in this run: foreign objects it cleared ("rewrote") or only saw ("saw") */
    fun events(bin: File): Int = runCatching {
        File(bin, LOG).useLines { ls -> ls.count { it.startsWith("[guard") && (it.contains("] rewrote foreign") || it.contains("] saw foreign")) } }
    }.getOrDefault(0)

    fun resetLog(bin: File) { runCatching { File(bin, LOG).delete() } }

    /** size and SHA-1 prefix of the binder daemon library: what the remembered decision is tied to */
    fun binderdId(lib: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        lib.inputStream().use { ins -> val buf = ByteArray(64 * 1024); while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        "${lib.length()}-" + md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("unknown")
}
