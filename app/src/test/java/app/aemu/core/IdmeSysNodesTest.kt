/* AEmulator Plus addition, 2026-10-17. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

/** Kindle Fire's libidme.so fopen()s a sysfs node for writing and uses the result unchecked (see TreeFixer.idmeSysNodes). */
class IdmeSysNodesTest {
    private fun blob(vararg strings: String): ByteArray =
        ("\u0000\u0001ELF-ish\u0000" + strings.joinToString("\u0000") + "\u0000\u0002").toByteArray(Charsets.ISO_8859_1)

    @Test fun findsALiteralNodeInsideABinaryStringTable() {
        assertEquals(listOf("/sys/class/mmc_host/mmc0/mmc0:0001/shift"),
            TreeFixer.idmeSysPaths(blob("w", "/sys/class/mmc_host/mmc0/mmc0:0001/shift", "idme")))
    }
    @Test fun findsSeveralAndKeepsOrder() {
        assertEquals(listOf("/sys/a/b/c", "/sys/x/y/z/w"), TreeFixer.idmeSysPaths(blob("/sys/a/b/c", "/sys/x/y/z/w")))
    }
    @Test fun skipsDirectoriesFinishedByAFormatString() {
        assertTrue(TreeFixer.idmeSysPaths(blob("/sys/class/mmc_host/mmc0/mmc0:0001/%s")).isEmpty())
        assertTrue(TreeFixer.idmeSysPaths(blob("/sys/class/mmc_host/mmc0/mmc0:0001/")).isEmpty())
        assertTrue(TreeFixer.idmeSysPaths(blob("/sys/class/block/mmcblk%d/size")).isEmpty())
    }
    @Test fun skipsShortAndEscapingPaths() {
        assertTrue(TreeFixer.idmeSysPaths(blob("/sys/class", "/sys/class/net", "/sys/")).isEmpty())
        assertTrue(TreeFixer.idmeSysPaths(blob("/sys/a/../../data/x")).isEmpty())
    }
    @Test fun trailingSentenceDotIsNotPartOfThePath() {
        assertEquals(listOf("/sys/a/b/c"), TreeFixer.idmeSysPaths(blob("cannot open /sys/a/b/c.")))
    }
    @Test fun neverTurnsADirectoryIntoAFile() {
        assertEquals(listOf("/sys/a/b/c/d"), TreeFixer.idmeNodesToCreate(listOf("/sys/a/b/c", "/sys/a/b/c/d")))
        assertEquals(listOf("/sys/a/b/c1", "/sys/a/b/c"), TreeFixer.idmeNodesToCreate(listOf("/sys/a/b/c1", "/sys/a/b/c"))) // a name prefix is not a directory
    }
    @Test fun onlyCreatesUnderSys() {
        assertTrue(TreeFixer.idmeNodesToCreate(listOf("/dev/foo/bar/baz", "/data/x", "sys/a/b/c", "/sys/a/b/c/")).isEmpty())
    }
    @Test fun theNodeFromTheCrashIsKept() {
        assertEquals(listOf("/sys/class/mmc_host/mmc0/mmc0:0001/shift"),
            TreeFixer.idmeNodesToCreate(listOf("/sys/class/mmc_host/mmc0/mmc0:0001/shift")))
    }
}
