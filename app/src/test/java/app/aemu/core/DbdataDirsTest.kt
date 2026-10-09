package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class DbdataDirsTest {
    @Test fun samsungRcMountingDbdataGetsTheDirectories() {
        val rc = "on fs\n    mount rfs /dev/block/stl10 /dbdata nosuid nodev check=no\n"
        assertEquals(listOf("dbdata", "dbdata/databases", "dbdata/system"), TreeFixer.dbdataDirs(listOf(rc), emptyList()))
    }

    @Test fun mkdirLineIsEnough() {
        assertTrue(TreeFixer.dbdataDirs(emptyList(), listOf("/dbdata/databases")).isNotEmpty())
    }

    @Test fun otherFirmwareIsLeftAlone() {
        assertTrue(TreeFixer.dbdataDirs(listOf("on fs\n    mount ext4 /dev/block/mmcblk0p9 /data\n"), listOf("/data/misc")).isEmpty())
    }
}
