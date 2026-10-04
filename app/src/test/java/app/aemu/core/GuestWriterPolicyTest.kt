/* AEmulator Sunset addition, 2026-10-02. GPL-3.0; see LICENSE.
 * Modified for AEmulator Plus, 2026-10-04: package app.aemu.plus, clone variant removed. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class GuestWriterPolicyTest {
    @Test fun detectsNativeOrphansAndSeparateBridgeProcesses() {
        for (name in listOf("libqemu-arm.so", "libbinderd.so", "libdhdrun.so", "libglserverd.so"))
            assertTrue(GuestWriterPolicy.matches("/lib/$name -L /app/images/a9dyxj/root", "app.aemu.plus", "/app/images"))
        assertTrue(GuestWriterPolicy.matches("app.aemu.plus:binder", "app.aemu.plus", "/app/images"))
        assertTrue(GuestWriterPolicy.matches("app.aemu.plus:gl", "app.aemu.plus", "/app/images"))
    }
    @Test fun unrelatedProcessesAndOtherPackagesAreNotWriters() {
        for (command in listOf("app.aemu.plus", "app.aemu.plus:vm", "app.aemu:binder", "app.aemu:gl", "/lib/libqemu.so -L /other/images/a9dyxj/root", "/lib/tool /app/images/a9dyxj/root"))
            assertFalse(GuestWriterPolicy.matches(command, "app.aemu.plus", "/app/images"))
    }
}
