/* Added for AEmulator Plus, 2026-11-09: the tabs of the one-window multi-VM interface; a tab may hold two machines
 * (split screen). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.RandomAccessFile

/**
 * The open tabs, in order, shared by every process (the library and each VM process draw the same tab bar).
 * A tab is one machine's window, or two machines on one screen. Closing a tab by "minimize" only removes it from here,
 * the machine keeps running in its process and can be opened again from the library.
 *
 * File format, one tab per line: `id` for a machine, `base+overlay@P` for two machines, where P (T, B, L or R) is the side
 * of the screen the second ("overlay") machine is on; the first ("base") machine has the opposite side.
 */
object TabRegistry {
    /** broadcast (package-local): the list of tabs changed; every machine window re-reads it and fits itself */
    const val ACTION_CHANGED = "app.aemu.TABS_CHANGED"

    /** One tab: [ids] has one machine, or two (base first) that share the screen, the second one on side [pos]. */
    class Tab(val ids: List<String>, val pos: Char? = null, val names: List<String> = ids) {
        val isGroup get() = ids.size > 1
        val id get() = ids[0]
        fun otherThan(id: String): String? = ids.firstOrNull { it != id }
        /** the machine with the translucent partial window that is on top of the other one */
        fun isOverlay(id: String) = isGroup && id == ids[1]
        /** the side of the screen the machine's window is on (null: the whole screen) */
        fun paneOf(id: String): Char? = when {
            !isGroup || pos == null -> null
            id == ids[1] -> pos
            else -> SplitLayout.opposite(pos)
        }
    }

    private val mem = Any()
    private val LINE = Regex("([a-z0-9]+)(?:\\+([a-z0-9]+)@([TBLR]))?")
    private fun dir(ctx: Context) = File(ctx.filesDir, "slots").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "tabs.txt")

    internal fun parse(line: String): Tab? {
        val m = LINE.matchEntire(line.trim()) ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        return if (b.isEmpty() || a == b) Tab(listOf(a)) else Tab(listOf(a, b), m.groupValues[3][0])
    }

    internal fun format(t: Tab): String = if (t.isGroup) "${t.ids[0]}+${t.ids[1]}@${t.pos}" else t.ids[0]

    /** the tabs in order, as stored (a machine is in at most one of them) */
    fun entries(ctx: Context): List<Tab> = runCatching {
        val seen = HashSet<String>()
        file(ctx).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { parse(it) }.filter { t -> t.ids.none { it in seen } && seen.addAll(t.ids) }
    }.getOrDefault(emptyList())

    /** every machine that has a tab, in order */
    fun read(ctx: Context): List<String> = entries(ctx).flatMap { it.ids }

    fun entryOf(ctx: Context, id: String): Tab? = entries(ctx).firstOrNull { id in it.ids }

    private fun write(ctx: Context, tabs: List<Tab>) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "tabs.tmp")
        tmp.writeText(tabs.joinToString("\n") { format(it) })
        check(tmp.renameTo(f)) { "Cannot save the tab list" }
    }

    private fun <T> locked(ctx: Context, body: () -> T): T = synchronized(mem) {
        RandomAccessFile(File(dir(ctx), "tabs.lock"), "rw").use { raf -> raf.channel.lock().use { body() } }
    }

    /** every machine window re-reads the tabs and fits itself (a window of a machine that has no tab any more closes) */
    fun notifyChanged(ctx: Context) {
        runCatching { ctx.sendBroadcast(Intent(ACTION_CHANGED).setPackage(ctx.packageName)) }
    }

    fun open(ctx: Context, id: String) {
        runCatching { locked(ctx) { val l = entries(ctx); if (l.none { id in it.ids }) write(ctx, l + Tab(listOf(id))) } }
    }

    /** The machine leaves its tab: a tab of one machine goes, of two the other machine keeps the tab (full screen again). */
    fun close(ctx: Context, id: String) {
        val changed = runCatching {
            locked(ctx) {
                val l = entries(ctx)
                if (l.none { id in it.ids }) false
                else { write(ctx, l.mapNotNull { t -> if (id !in t.ids) t else t.otherThan(id)?.let { Tab(listOf(it)) } }); true }
            }
        }.getOrDefault(false)
        if (changed) notifyChanged(ctx)
    }

    /**
     * Puts [overlay] on the tab of [base], on side [pos] of the screen. Both must be tabs of one machine each.
     * False when that is not so.
     */
    fun split(ctx: Context, base: String, overlay: String, pos: Char): Boolean {
        if (base == overlay || !SplitLayout.isSide(pos)) return false
        val done = runCatching {
            locked(ctx) {
                val l = entries(ctx)
                val b = l.indexOfFirst { it.ids == listOf(base) }
                val o = l.indexOfFirst { it.ids == listOf(overlay) }
                if (b < 0 || o < 0) false
                else {
                    write(ctx, l.mapIndexedNotNull { i, t -> if (i == b) Tab(listOf(base, overlay), pos) else if (i == o) null else t })
                    true
                }
            }
        }.getOrDefault(false)
        if (done) notifyChanged(ctx)
        return done
    }

    /** A tab of two machines becomes two tabs of one machine each. */
    fun separate(ctx: Context, id: String) {
        val changed = runCatching {
            locked(ctx) {
                val l = entries(ctx)
                val t = l.firstOrNull { id in it.ids }
                if (t == null || !t.isGroup) false
                else { write(ctx, l.flatMap { if (it === t) it.ids.map { m -> Tab(listOf(m)) } else listOf(it) }); true }
            }
        }.getOrDefault(false)
        if (changed) notifyChanged(ctx)
    }

    /** The tabs that are still backed by machines' processes (named); a tab whose process is gone is dropped from the list. */
    fun live(ctx: Context): List<Tab> {
        val snap = VmSlots.snapshot(ctx)
        val tabs = runCatching {
            locked(ctx) {
                val all = entries(ctx)
                if (!snap.reliable) all
                else all.mapNotNull { t ->
                    val alive = t.ids.filter { snap.slotOf(it) != null }
                    when {
                        alive.size == t.ids.size -> t
                        alive.isEmpty() -> null
                        else -> Tab(alive) // the other machine of the pair is gone: the tab goes on with this one
                    }
                }.also { if (it.map(::format) != all.map(::format)) write(ctx, it) }
            }
        }.getOrDefault(emptyList())
        return tabs.map { t -> Tab(t.ids, t.pos, t.ids.map { nameOf(ctx, it) }) }
    }

    private val names = HashMap<String, Pair<String, Long>>()
    private fun nameOf(ctx: Context, id: String): String {
        val now = System.currentTimeMillis()
        synchronized(names) { names[id]?.takeIf { now - it.second < 5_000 }?.let { return it.first } }
        val n = ImageStore.get(ctx, id)?.name?.trim()?.takeIf { it.isNotEmpty() } ?: id
        synchronized(names) { names[id] = n to now }
        return n
    }

    /** "Name of a machine" -> at most 10 characters, then "..." */
    fun shortName(name: String): String {
        val n = name.trim()
        return if (n.codePointCount(0, n.length) > 10) n.substring(0, n.offsetByCodePoints(0, 10)) + "..." else n
    }
}
