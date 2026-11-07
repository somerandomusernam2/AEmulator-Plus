package app.aemu.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The real adb client (platform-tools) against [AdbConn]: the handshake of a computer seen for the first time, of one
 * allowed before, of one refused, and no handshake at all with authorization switched off. Skipped where no adb is
 * installed (ANDROID_HOME, ANDROID_SDK_ROOT, local.properties or PATH).
 */
class AdbWireTest {
    @get:Rule val tmp = TemporaryFolder()

    private var adb: File? = null
    private lateinit var home: File
    private lateinit var auth: AdbAuth
    private var device: ServerSocket? = null
    private val adbServerPort = ServerSocket(0).use { it.localPort }
    private val asks = AtomicInteger()
    @Volatile private var answer = AdbAuth.Answer.ALWAYS
    @Volatile private var required = true

    @Before fun setUp() {
        adb = findAdb()
        assumeTrue("no adb found", adb != null)
        home = tmp.newFolder("home")
        auth = AdbAuth(File(tmp.root, "adb_keys"))
    }

    @After fun tearDown() {
        if (adb != null) runCatching { adb("kill-server") }
        runCatching { device?.close() }
    }

    /** the phone's side: AdbConn with a "shell:" service that only says what it was asked to run */
    private fun startDevice(): String {
        val ss = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        device = ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        AdbConn(c, auth, { required }, { _, _ -> asks.incrementAndGet(); answer },
                            { "device::ro.product.name=aemu;ro.product.model=test;ro.product.device=aemu;\u0000" }, {}) { s, service ->
                            if (service.startsWith("shell:")) s.out.write("ran ${service.removePrefix("shell:")}\n".toByteArray())
                        }.run()
                    }
                    runCatching { c.close() }
                }
            }
        }
        return "127.0.0.1:${ss.localPort}"
    }

    private fun adb(vararg args: String): String {
        val pb = ProcessBuilder(listOf(adb!!.path, "-P", "$adbServerPort") + args).redirectErrorStream(true)
        val env = pb.environment()
        for (k in listOf("ANDROID_SDK_HOME", "ANDROID_USER_HOME", "ADB_VENDOR_KEYS", "ANDROID_SERIAL")) env.remove(k)
        env["HOME"] = home.path
        env["ADB_MDNS"] = "0"
        val p = pb.start()
        val out = StringBuilder()
        val t = thread(isDaemon = true) { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
        if (!p.waitFor(40, TimeUnit.SECONDS)) p.destroyForcibly()
        t.join(2000)
        return out.toString()
    }

    private fun state(serial: String): String? =
        adb("devices").lines().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size >= 2 && it[0] == serial }?.get(1)

    private fun waitState(serial: String, want: String, ms: Long = 15_000): String? {
        val until = System.currentTimeMillis() + ms
        var st: String? = null
        while (System.currentTimeMillis() < until) {
            st = state(serial)
            if (st == want) return st
            Thread.sleep(300)
        }
        return st
    }

    @Test fun newComputerIsAskedOnceThenKnownByItsKey() {
        val serial = startDevice()
        adb("connect", serial)
        assertEquals("device", waitState(serial, "device"))
        assertEquals(1, asks.get())
        assertTrue(adb("-s", serial, "shell", "echo", "hi").contains("ran echo hi"))
        assertEquals(1, File(tmp.root, "adb_keys").readLines().count { it.isNotBlank() })

        // a new adb server: the same key signs the token, nobody is asked again
        adb("kill-server")
        adb("connect", serial)
        assertEquals("device", waitState(serial, "device"))
        assertEquals(1, asks.get())
        assertTrue(adb("-s", serial, "shell", "echo", "again").contains("ran echo again"))
    }

    @Test fun refusedComputerGetsNothing() {
        answer = AdbAuth.Answer.DENY
        val serial = startDevice()
        adb("connect", serial)
        Thread.sleep(3000)
        assertFalse(state(serial) == "device")
        assertTrue(asks.get() >= 1)
        assertFalse(adb("-s", serial, "shell", "echo", "hi").contains("ran echo hi"))
        assertFalse(File(tmp.root, "adb_keys").exists())
    }

    @Test fun withoutAuthorizationEveryClientIsServedAsBefore() {
        required = false
        val serial = startDevice()
        adb("connect", serial)
        assertEquals("device", waitState(serial, "device"))
        assertEquals(0, asks.get())
        assertTrue(adb("-s", serial, "shell", "echo", "hi").contains("ran echo hi"))
    }

    private fun findAdb(): File? {
        val sdks = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")) +
            listOf("../local.properties", "local.properties").mapNotNull { n ->
                File(n).takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir") }
            }
        val name = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"
        return (sdks.map { File(it, "platform-tools/$name") } +
            System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).map { File(it, name) })
            .firstOrNull { it.canExecute() }
    }
}
