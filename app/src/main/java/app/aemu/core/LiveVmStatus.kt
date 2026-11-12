/* AEmulator Sunset addition, 2026-10-02. Modified for AEmulator Plus, 2026-11-09: one lease per VM slot. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Advisory lease shared by :vm and the library. Kernel releases it on process death.
 * A leftover ID file alone never means that a guest is running. */
internal class LiveVmStatus(private val directory: File, private val slot: Int = 0) {
    private val lockFile get() = file(directory, slot, "lock")
    private val idFile get() = file(directory, slot, "id")
    private var handle: RandomAccessFile? = null
    private var lease: FileLock? = null

    @Synchronized fun publish(id: String, active: Boolean) {
        if (!active) { close(); return }
        if (lease?.isValid == true) return
        directory.mkdirs()
        val opened = RandomAccessFile(lockFile, "rw")
        try {
            val acquired = opened.channel.tryLock() ?: error("VM status lease already held")
            handle = opened; lease = acquired
            idFile.writeText(id)
        } catch (t: Throwable) { opened.close(); handle = null; lease = null; throw t }
    }

    @Synchronized fun close() {
        try { lease?.release() } finally { lease = null; handle?.close(); handle = null }
    }

    fun activeId(): String? = activeId(lockFile, idFile)

    companion object {
        /** slot 0 keeps the original file names */
        private fun file(directory: File, slot: Int, ext: String) = File(directory, if (slot <= 0) "live-vm.$ext" else "live-vm-$slot.$ext")

        private fun activeId(lockFile: File, idFile: File): String? = runCatching {
        if (!lockFile.isFile) return@runCatching null
        RandomAccessFile(lockFile, "rw").use { reader ->
            val probe = try { reader.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (probe != null) { probe.release(); null }
            else idFile.readText().trim().takeIf { it.matches(Regex("[a-z0-9]{6}")) }
        }
    }.getOrNull()
    }
}
