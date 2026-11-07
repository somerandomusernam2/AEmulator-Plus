package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class DeltaOtaLegacyTest {
    private val NEW = "0cc59154e863a7d3e32b41dfa20421df32bf992a"
    private val OLD = "f76adfbadc56e8719e8a84d02eb185ff4567b2fa"

    /** Android 1.x: applypatch <file> <new sha1> <new size> <old sha1>:<patch> */
    @Test fun applypatchLineKeepsNewAndOldHashesApart() {
        val line = "run_program PACKAGE:applypatch /system/app/AlarmClock.apk $NEW 178046 $OLD:/tmp/patchtmp/system/app/AlarmClock.apk.p"
        val edify = DeltaOta.legacyToEdify(line)
        // apply_patch(path, "-", new sha1, new size, old sha1, patch)
        assertEquals(
            "apply_patch(\"/system/app/AlarmClock.apk\", \"-\", $NEW, 178046, $OLD, " +
                "package_extract_file(\"patch/system/app/AlarmClock.apk.p\"));\n",
            edify,
        )
    }

    @Test fun checkLinesAndHousekeepingProduceNothing() {
        val text = """
            assert getprop("ro.product.device") == "dream"
            show_progress 0.012455 1
            run_program PACKAGE:applypatch -c /system/app/AlarmClock.apk $NEW $OLD
            run_program PACKAGE:applypatch -s 4918688
            copy_dir PACKAGE:patch CACHE:../tmp/patchtmp
            format BOOT:
        """.trimIndent()
        assertEquals("", DeltaOta.legacyToEdify(text))
    }

    @Test fun otherLegacyCommandsAreTranslated() {
        val text = "set_perm 0 3003 02755 SYSTEM:bin/netcfg\n" +
            "write_raw_image PACKAGE:boot.img BOOT:\n" +
            "delete DATA:data/com.android.browser/databases/webview.db\n"
        val edify = DeltaOta.legacyToEdify(text)
        assertTrue(edify.contains("set_perm(0, 3003, 02755, \"/system/bin/netcfg\");"))
        assertTrue(edify.contains("boot_image(\"boot.img\");"))
        assertTrue(edify.contains("delete(\"/data/data/com.android.browser/databases/webview.db\");"))
    }
}
