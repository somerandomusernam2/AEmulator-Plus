package app.aemu.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BinderGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    // lines as libbinderd_kk wrote them in run/binderd.log of a Sprd GT-N9300 (4.0.3) that hung at boot
    private val hung = """
=== binderd 10:32:34 ===
binderd: binderd слушает /data/user/0/app.aemu.plus/files/images/fpitbn/run/binder.sock

=== binderd 11:40:27 ===
binderd: binderd слушает /data/user/0/app.aemu.plus/files/images/fpitbn/run/binder.sock
binderd: ★★★ ОТВЕТ ГОЛЫМ ОБЪЕКТОМ (тип 0x73622a85) на код=1: отвечает pid=18020/18054 -> pid=18252/18269, смещений 1, у получателя незакрытых вызовов 1
binderd: ★★ ЗАВИСШАЯ ТРАНЗАКЦИЯ 21 с: от pid=18020/18054 -> pid=18252 код=1 флаги=0x10
binderd:   ★ неисправности с начала работы: работа возвращена в очередь 0, адресат мёртв 0, битых смещений 0, handle в пустоту 0, вложенных вызовов 0, ответов голым объектом 19, залежавшейся работы 24
""".trimStart()

    @Test fun detectsTheFaultOnlyInTheLastRunOfTheDaemon() {
        val run = QemuFallback.lastRun(hung, "binderd")
        assertTrue(BinderGuard.detect(run))
        assertEquals(19, BinderGuard.bareCount(run))
        // the earlier boots of the same file show nothing
        val first = hung.substringBefore("=== binderd 11:40:27")
        assertFalse(BinderGuard.detect(QemuFallback.lastRun(first, "binderd")))
    }

    @Test fun bareRepliesAloneAreNotEnough() {
        val run = hung.lines().filterNot { it.contains("ЗАВИСШАЯ") }.joinToString("\n")
        assertFalse(BinderGuard.detect(run))
    }

    @Test fun decisionIsRememberedPerDaemonBuild() {
        val bin = tmp.newFolder()
        assertNull(BinderGuard.state(bin))
        assertFalse(BinderGuard.armed(bin, "a"))
        BinderGuard.arm(bin, "a", "2026-11-12 11:54:00", 19)
        assertTrue(BinderGuard.armed(bin, "a"))
        assertFalse("another daemon build starts from scratch", BinderGuard.armed(bin, "b"))
        assertNull(BinderGuard.known(bin, "b"))
        BinderGuard.markIneffective(bin, "a", "2026-11-12 12:10:00", 20)
        assertFalse(BinderGuard.armed(bin, "a"))
        assertTrue(BinderGuard.known(bin, "a")!!.ineffective)
        BinderGuard.forget(bin)
        assertNull(BinderGuard.state(bin))
    }

    @Test fun countsWhatTheGuestGuardWrote() {
        val bin = tmp.newFolder()
        assertEquals(0, BinderGuard.events(bin))
        File(bin, BinderGuard.LOG).writeText(
            "[guard pid=18020 tid=18054] rewrote foreign object in BR_REPLY code=4 from_pid=18252 type=0x73622a85 binder=0x00005000 cookie=0x00006000 #1\n" +
                "[guard pid=18020 tid=18054] saw foreign object in BR_TRANSACTION code=1 from_pid=1 type=0x73622a85 binder=0x00000001 cookie=0x00000002 #2\n" +
                "[guard pid=18020 tid=18054] knowledge table full, guard now passes everything #3\n")
        assertEquals(2, BinderGuard.events(bin))
        BinderGuard.resetLog(bin)
        assertEquals(0, BinderGuard.events(bin))
    }

    @Test fun binderdIdFollowsTheLibrary() {
        val a = tmp.newFile("a.so").apply { writeText("one") }
        val b = tmp.newFile("b.so").apply { writeText("two") }
        assertNotNull(BinderGuard.binderdId(a))
        assertFalse(BinderGuard.binderdId(a) == BinderGuard.binderdId(b))
        assertEquals("unknown", BinderGuard.binderdId(File(tmp.root, "missing.so")))
    }

    @Test fun guardSitsInFrontOfTheShimAndOnlyWhenAsked() {
        assertEquals("/system/lib/libashmemshim.so:/system/lib/libaemushim.so:/system/lib/libaemurlimit.so", GuestPreload.value(false))
        assertEquals("/system/lib/libashmemshim.so:/system/lib/libaemubinderguard.so:/system/lib/libaemushim.so:/system/lib/libaemurlimit.so",
            GuestPreload.value(false, guard = true))
        assertEquals("glibc firmware never gets it", GuestPreload.value(true), GuestPreload.value(true, guard = true))
    }
}
