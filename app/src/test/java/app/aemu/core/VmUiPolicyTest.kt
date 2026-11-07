/* AEmulator Sunset addition, 2026-10-02. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.concurrent.TimeUnit

class VmUiPolicyTest {
    @Test fun logBackAlwaysStaysOnTheHost() {
        for (hidden in listOf(false, true))
            assertEquals(VmUiPolicy.BackAction.CLOSE_LOG, VmUiPolicy.back(true, hidden))
    }
    @Test fun ordinaryBackKeepsTheConfiguredBehavior() {
        assertEquals(VmUiPolicy.BackAction.OPEN_MENU, VmUiPolicy.back(false, true))
        assertEquals(VmUiPolicy.BackAction.GUEST_BACK, VmUiPolicy.back(false, false))
    }
    @Test fun onlyActiveStatesUseOpen() {
        listOf("PREPARING", "BOOTING", "RUNNING").forEach { assertTrue(VmUiPolicy.active(it)) }
        listOf("STOPPED", "STOPPING", "FAILED", "unknown").forEach { assertFalse(VmUiPolicy.active(it)) }
    }
    // area 400x800, button 100x100: it can travel up to 300 left and 700 down from its top-right resting spot
    @Test fun menuButtonInsideTheBorderIsLeftAlone() {
        assertEquals(0f to 0f, VmUiPolicy.clampMenuOffset(0f, 0f, 400, 800, 100, 100))
        assertEquals(-120f to 250f, VmUiPolicy.clampMenuOffset(-120f, 250f, 400, 800, 100, 100))
        assertEquals(-300f to 700f, VmUiPolicy.clampMenuOffset(-300f, 700f, 400, 800, 100, 100))
    }
    @Test fun menuButtonCannotBeDraggedPastAnyEdge() {
        assertEquals(0f to 0f, VmUiPolicy.clampMenuOffset(50f, -50f, 400, 800, 100, 100)) // right / up
        assertEquals(-300f to 700f, VmUiPolicy.clampMenuOffset(-5000f, 5000f, 400, 800, 100, 100)) // left / down
    }
    @Test fun menuButtonFromASmallerScreenIsPulledBackAfterRotation() {
        // saved while portrait (-300,700); landscape is 800x400
        assertEquals(-300f to 300f, VmUiPolicy.clampMenuOffset(-300f, 700f, 800, 400, 100, 100))
    }
    @Test fun unmeasuredOrTooSmallAreaPinsTheMenuButtonToItsCorner() {
        assertEquals(0f to 0f, VmUiPolicy.clampMenuOffset(-90f, 90f, 0, 0, 0, 0))
        assertEquals(0f to 0f, VmUiPolicy.clampMenuOffset(-90f, 90f, 80, 80, 100, 100))
    }
    @Test fun damagedSavedMenuPositionFallsBackToTheCorner() {
        assertEquals(0f to 0f, VmUiPolicy.clampMenuOffset(Float.NaN, Float.POSITIVE_INFINITY, 400, 800, 100, 100))
    }
    @Test fun staleStatusAndClosedLeaseDoNotReportRunning() {
        val directory = Files.createTempDirectory("sunset-vm-status-").toFile()
        val writer = LiveVmStatus(directory)
        val reader = LiveVmStatus(directory)
        try {
            assertNull(reader.activeId())
            writer.publish("a9dyxj", true)
            assertEquals("a9dyxj", reader.activeId())
            writer.publish("a9dyxj", false)
            assertNull(reader.activeId())
            writer.publish("fc73qc", true)
            assertEquals("fc73qc", reader.activeId())
            writer.close()
            assertNull(reader.activeId())
        } finally { writer.close(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    @Test fun aRealProcessExitReleasesTheLeaseWithoutCleaningTheMarker() {
        val directory = Files.createTempDirectory("sunset-vm-process-").toFile()
        val javaBin = File(System.getProperty("java.home"), "bin/java").let {
            if (it.isFile) it else File(it.path + ".exe")
        }
        val classpath = listOf(LiveVmStatus::class.java, LiveVmLeaseProbe::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val child = ProcessBuilder(javaBin.path, "-cp", classpath, LiveVmLeaseProbe::class.java.name, directory.path)
            .redirectErrorStream(true).start()
        try {
            assertEquals("ready", child.inputStream.bufferedReader().readLine())
            val reader = LiveVmStatus(directory)
            assertEquals("a9dyxj", reader.activeId())
            assertEquals("a9dyxj", reader.activeId()) // Closing the reader must not release the writer's lock.
            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertTrue(File(directory, "live-vm.id").isFile)
            assertNull(reader.activeId())
        } finally {
            child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS)
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
}

/** Separate-process fixture: deliberately exits without closing or clearing its lease. */
object LiveVmLeaseProbe {
    @JvmStatic fun main(args: Array<String>) {
        val status = LiveVmStatus(File(args.single()))
        status.publish("a9dyxj", true)
        println("ready")
        Thread.sleep(60_000)
        status.close()
    }
}
