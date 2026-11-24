package app.aemu.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class FolderShare(sdcard: File, private val stateFile: File, private val log: (String) -> Unit) {
    internal class St(val dir: Boolean, val size: Long, val mtime: Long)
    private class Copy(val fromHost: Boolean, val rel: String, val st: St)

    private val host = File(Environment.getExternalStorageDirectory(), DIR)
    private val guest = File(sdcard, DIR)
    private val off = File(stateFile.parentFile ?: sdcard, SWITCH)
    private val running = AtomicBoolean(false)
    private val state = HashMap<String, Pair<St?, St?>>()
    private var worker: Thread? = null
    private var lastError = ""
    private var cleared = false

    fun setEnabled(v: Boolean) {
        runCatching {
            off.parentFile?.mkdirs()
            if (v) off.delete() else off.writeText("")
        }
        cleared = false
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        load()
        worker = thread(name = "folder-share", isDaemon = true) { loop() }
        log("folder share: ${host.absolutePath} <-> ${guest.absolutePath}${if (off.exists()) ", off" else ""}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.join(8_000)
        log("folder share: stopped")
    }

    private fun loop() {
        while (running.get()) {
            if (off.exists()) {
                if (!cleared) { cleared = true; clearGuest() }
            } else {
                cleared = false
                runCatching { sync() }.onFailure { fail(it) }
            }
            if (!running.get()) break
            runCatching { Thread.sleep(POLL_MS) }
        }
    }

    private fun clearGuest() {
        fun rm(f: File) {
            val at = runCatching { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return
            if (at.isDirectory) f.listFiles()?.forEach { rm(it) }
            runCatching { Files.deleteIfExists(f.toPath()) }
        }
        runCatching { rm(guest) }
        state.clear()
        runCatching { stateFile.delete() }
        log("folder share: guest copy removed")
    }

    private fun ensureRoots(): Boolean {
        runCatching { host.mkdirs() }
        runCatching { guest.mkdirs() }
        val ha = lstat(host)
        val ga = lstat(guest)
        if (ha?.isDirectory != true || ga?.isDirectory != true) { fail("no usable shared folder"); return false }
        return true
    }

    private fun sync() {
        if (!ensureRoots()) return
        val ca = scan(host)
        val cb = scan(guest)
        val keys = LinkedHashSet<String>()
        keys.addAll(ca.keys)
        keys.addAll(cb.keys)
        keys.addAll(state.keys)
        val copies = ArrayList<Copy>()
        val deletes = ArrayList<Pair<Int, String>>()
        val failed = HashSet<String>()
        for (rel in keys) {
            val a = ca[rel]
            val b = cb[rel]
            val o = state[rel]
            val oa = o?.first
            val ob = o?.second
            when {
                a != null && b != null -> {
                    val aSame = oa != null && same(a, oa)
                    val bSame = ob != null && same(b, ob)
                    when {
                        aSame && bSame -> Unit
                        aSame -> copies.add(Copy(false, rel, b))
                        bSame -> copies.add(Copy(true, rel, a))
                        a.mtime >= b.mtime -> copies.add(Copy(true, rel, a))
                        else -> copies.add(Copy(false, rel, b))
                    }
                }
                a != null -> if (oa != null && same(a, oa)) deletes.add(0 to rel) else copies.add(Copy(true, rel, a))
                b != null -> if (ob != null && same(b, ob)) deletes.add(1 to rel) else copies.add(Copy(false, rel, b))
            }
        }
        for (c in copies) runCatching { copy(c) }.onFailure { failed.add(c.rel); fail(it) }
        for (d in deletes.sortedWith(compareByDescending<Pair<Int, String>> { it.second })) runCatching { delete(d.first, d.second) }.onFailure { fail(it) }
        val na = scan(host)
        val nb = scan(guest)
        val ns = HashMap<String, Pair<St?, St?>>()
        for (rel in keys) if (rel !in failed && (na.containsKey(rel) || nb.containsKey(rel))) ns[rel] = na[rel] to nb[rel]
        state.clear()
        state.putAll(ns)
        if (copies.isNotEmpty() || deletes.isNotEmpty()) save()
        lastError = ""
    }

    private fun same(cur: St, old: St) = cur.dir == old.dir && (cur.dir || (cur.size == old.size && cur.mtime == old.mtime))

    private fun copy(c: Copy) {
        val srcRoot = if (c.fromHost) host else guest
        val dstRoot = if (c.fromHost) guest else host
        val src = safe(srcRoot, c.rel) ?: return fail("unsafe path ${c.rel}")
        val at = lstat(src) ?: return
        if (at.isDirectory != c.st.dir) return fail("type mismatch ${c.rel}")
        if (c.st.dir) {
            ensureDir(dstRoot, c.rel)
            return
        }
        if (!at.isRegularFile) return fail("not a regular file ${c.rel}")
        val dir = ensureDir(dstRoot, parent(c.rel)) ?: return fail("unsafe path ${c.rel}")
        val dst = File(dir, name(c.rel))
        transfer(src, dst)
        runCatching { if (lstat(dst)?.isSymbolicLink == false) dst.setLastModified(at.lastModifiedTime().toMillis()) }
    }

    private fun delete(side: Int, rel: String) {
        val root = if (side == 0) host else guest
        val f = safe(root, rel) ?: return
        runCatching { Files.deleteIfExists(f.toPath()) }.onFailure { fail(it) }
    }

    private fun transfer(src: File, dst: File) {
        FileChannel.open(src.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { i ->
            FileChannel.open(dst.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { o ->
                val total = i.size()
                var left = total
                while (left > 0) left -= i.transferTo(total - left, left, o)
            }
        }
    }

    private fun lstat(f: File): BasicFileAttributes? =
        runCatching { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()

    private fun safe(root: File, rel: String): File? {
        var f = root
        for (part in rel.split('/')) {
            if (part.isEmpty() || part == "." || part == "..") return null
            f = File(f, part)
            val at = lstat(f) ?: return null
            if (at.isSymbolicLink) return null
        }
        return f
    }

    private fun ensureDir(root: File, rel: String): File? {
        if (rel.isEmpty()) return root
        var f = root
        for (part in rel.split('/')) {
            if (part.isEmpty() || part == "." || part == "..") return null
            f = File(f, part)
            val at = lstat(f)
            if (at == null) {
                runCatching { f.mkdir() }
                val made = lstat(f)
                if (made == null || made.isSymbolicLink || !made.isDirectory) return null
            } else if (at.isSymbolicLink || !at.isDirectory) return null
        }
        return f
    }

    private fun parent(rel: String) = rel.substringBeforeLast('/', "")

    private fun name(rel: String) = rel.substringAfterLast('/')

    private fun scan(root: File): Map<String, St> {
        val out = TreeMap<String, St>()
        fun walk(dir: File, prefix: String) {
            val list = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in list) {
                val at = lstat(f) ?: continue
                if (at.isSymbolicLink) continue
                val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                if (at.isDirectory) {
                    out[rel] = St(true, 0, at.lastModifiedTime().toMillis())
                    walk(f, rel)
                } else if (at.isRegularFile) out[rel] = St(false, at.size(), at.lastModifiedTime().toMillis())
            }
        }
        if (root.isDirectory) walk(root, "")
        return out
    }

    private fun fail(t: Throwable) = fail(t.toString())

    private fun fail(msg: String) {
        if (msg != lastError) {
            lastError = msg
            log("folder share: $msg")
        }
    }

    private fun load() {
        state.clear()
        runCatching {
            DataInputStream(stateFile.inputStream().buffered()).use { d ->
                repeat(d.readInt()) {
                    val rel = d.readUTF()
                    val a = readSt(d)
                    val b = readSt(d)
                    state[rel] = a to b
                }
            }
        }
    }

    private fun readSt(d: DataInputStream): St? = when (d.readByte().toInt()) {
        1 -> St(true, 0, 0)
        2 -> St(false, d.readLong(), d.readLong())
        else -> null
    }

    private fun save() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            DataOutputStream(stateFile.outputStream().buffered()).use { d ->
                d.writeInt(state.size)
                for ((rel, pair) in state) {
                    d.writeUTF(rel)
                    writeSt(d, pair.first)
                    writeSt(d, pair.second)
                }
            }
        }.onFailure { fail(it) }
    }

    private fun writeSt(d: DataOutputStream, st: St?) {
        if (st == null) { d.writeByte(0); return }
        if (st.dir) { d.writeByte(1); return }
        d.writeByte(2)
        d.writeLong(st.size)
        d.writeLong(st.mtime)
    }

    companion object {
        internal const val DIR = "AEmulatorPlus"
        private const val POLL_MS = 1_000L
        private const val SWITCH = "folder-share.off"

        internal fun access(ctx: Context): Boolean = when {
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            else -> ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
}
