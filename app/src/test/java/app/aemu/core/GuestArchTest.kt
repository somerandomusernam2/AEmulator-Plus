package app.aemu.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestArchTest {
    private fun elf(machine: Int, cls: Int = 1): ByteArray = ByteArray(64).also {
        it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = cls.toByte(); it[5] = 1
        it[18] = (machine and 0xff).toByte(); it[19] = (machine shr 8).toByte()
    }

    private fun tree(machine: Int?, cls: Int = 1): File {
        val root = Files.createTempDirectory("guestarch").toFile()
        File(root, "system/lib").mkdirs()
        if (machine != null) File(root, "system/lib/libc.so").writeBytes(elf(machine, cls))
        return root
    }

    @Test fun x86ByElfEvenWhenAbiSaysArm() {
        assertEquals(GuestArch.X86, GuestArch.detect(tree(3), "armeabi-v7a"))
    }

    @Test fun armByElfEvenWhenAbiSaysX86() {
        assertEquals(GuestArch.ARM, GuestArch.detect(tree(40), "x86"))
    }

    @Test fun abiIsTheFallbackWhenThereIsNoElf() {
        assertEquals(GuestArch.X86, GuestArch.detect(tree(null), "x86"))
        assertEquals(GuestArch.ARM, GuestArch.detect(tree(null), "armeabi-v7a"))
        assertEquals(GuestArch.ARM, GuestArch.detect(tree(null), "x86_64"))
    }

    @Test fun unsupportedMachines() {
        assertFalse(GuestArch.isUnsupported(tree(3), "x86"))
        assertFalse(GuestArch.isUnsupported(tree(40), "armeabi-v7a"))
        assertFalse(GuestArch.isUnsupported(tree(183, 2), "arm64-v8a"))
        assertTrue(GuestArch.isUnsupported(tree(62, 2), "x86_64"))
        assertTrue(GuestArch.isUnsupported(tree(8), "mips"))
        assertTrue(GuestArch.isUnsupported(tree(null), "x86_64"))
    }

    @Test fun absoluteSymlinkIsNotFollowedToTheHost() {
        val root = tree(null)
        File(root, "system/bin").mkdirs()
        // the phone itself has /system/bin/sh, an arm64 file: it must not decide the guest's architecture
        Files.createSymbolicLink(File(root, "system/bin/sh").toPath(), File("/system/bin/sh").toPath())
        File(root, "system/lib/libc.so").writeBytes(elf(3))
        assertEquals(GuestArch.X86, GuestArch.detect(root, "armeabi-v7a"))
    }

    @Test fun noSmcOnlyOnArm() {
        assertTrue(GuestArch.ARM.supportsNoSmc)
        assertFalse(GuestArch.X86.supportsNoSmc)
    }

    @Test fun translators() {
        assertEquals("libqemu_kk.so", GuestArch.ARM.translator(Engine.KK))
        assertEquals("libqemu_kk_xperiaplay.so", GuestArch.ARM.translator(Engine.KK, true))
        assertEquals("libqemu_i386.so", GuestArch.X86.translator(Engine.KK))
    }

    @Test fun preloadOfAnotherArchitectureIsRecognized() {
        val d = Files.createTempDirectory("preload").toFile()
        val arm = File(d, "a.so").apply { writeBytes(elf(40)) }
        val x86 = File(d, "b.so").apply { writeBytes(elf(3)) }
        assertFalse(GuestArch.isOfArch(arm, GuestArch.X86))
        assertTrue(GuestArch.isOfArch(x86, GuestArch.X86))
        assertFalse(GuestArch.isOfArch(File(d, "missing.so"), GuestArch.X86))
    }
}
