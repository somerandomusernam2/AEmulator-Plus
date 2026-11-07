package app.aemu.importer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InsideTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun entriesOfAnArchiveStayInTheRoot() {
        val root = File(tmp.root, "root").apply { mkdirs() }
        val base = root.canonicalPath
        assertTrue(inside(File(root, "system/build.prop"), base))
        assertTrue(inside(File(root, "system/../vendor/x"), base))
        assertTrue(inside(root, base))
        assertFalse(inside(File(root, "../outside"), base))
        assertFalse(inside(File(root, "../../etc/passwd"), base))
        // a sibling whose name only starts like the root's
        assertFalse(inside(File(root, "../root2/x"), base))
        assertFalse(inside(File(root, "../root.bak"), base))
    }
}
