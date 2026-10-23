package app.aemu.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Pre-1.0 firmware (HTC "Sooner" 2007 builds, SDK-emulator packages that carry one): see [LegacyFirmware]. */
class LegacyFirmwareTest {

    /** The shape of the real /etc/init.rc of build 20645 (trimmed): env, startup and service sections, comments, quotes, both args forms. */
    private val oldRc = """
        ## Global environment setup
        env {
        	PATH /sbin:/android/sbin:/android/bin
        	LD_LIBRARY_PATH /android/lib
        	ANDROID_BOOTLOGO 1
        }
        startup {
        	reboot_keys {
        		exec /sbin/setkey
        		args {
        			0 -t 0      # no modifiers
        			1 -k 0xe5   # KEY_SOFT1
        		}
        	}
        	sdcard_2 {
        		exec /sbin/mount
        		args.0 -t
        		args.1 vfat
        		args.2 /dev/mmcblk0
        		args.3 /sdcard
        	}
        	rfcomm0_prep_2 {
        		exec /android/bin/sdptool 
        		args.0 setattr
        		args.1 "Serial Port 0"
        		autostart 0
        	}
        }
        service {
        	console {
        		exec /sbin/sh
        		console yes
        	}
        	runtime {
        		exec /android/bin/runtime
        #		autostart 0
        	}
        	hcid {
        		exec /android/bin/hcid
        		args.0 -n
        		args.1 -f
        		args.2 /etc/hcid.conf
        		autostart 0
        	}
        }
    """.trimIndent()

    private val modernRc = "on init\n    export PATH /sbin:/system/bin\nservice zygote /system/bin/app_process -Xzygote /system/bin --zygote\n    class main\n"

    private fun tree(test: (File) -> Unit) {
        val root = Files.createTempDirectory("aess-legacy").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    /** system/ of build 20645: javalib instead of framework, and a runtime linked against /android. */
    private fun legacySystem(root: File) {
        File(root, "system/javalib").mkdirs()
        File(root, "system/javalib/framework.jar").writeText("jar")
        File(root, "system/bin").mkdirs()
        File(root, "system/bin/runtime").writeBytes(ByteArray(64) + "/android/bin/linker".toByteArray() + ByteArray(8))
    }

    // ---- ramdisk

    @Test fun initScriptIsFoundAtTheRootOrInEtc() {
        assertTrue(LegacyFirmware.hasInitScript(listOf("init", "init.rc")))
        assertTrue(LegacyFirmware.hasInitScript(listOf("init", "etc/init.rc", "sbin/sh")))
        assertTrue(LegacyFirmware.hasInitScript(listOf("./etc/init.rc")))
        assertTrue(LegacyFirmware.hasInitScript(listOf("/init.rc")))
        assertFalse(LegacyFirmware.hasInitScript(listOf("init", "sbin/adbd", "etc/hosts")))
        assertFalse(LegacyFirmware.hasInitScript(listOf("system/etc/init.rc")))
    }

    @Test fun oldBlockSyntaxIsToldFromTheTriggerSyntax() {
        assertTrue(LegacyFirmware.isLegacyInitRc(oldRc))
        assertFalse(LegacyFirmware.isLegacyInitRc(modernRc))
        assertFalse(LegacyFirmware.isLegacyInitRc(""))
    }

    // ---- init.rc

    @Test fun envBecomesExports() {
        val rc = LegacyFirmware.parseInitRc(oldRc)
        assertEquals("/sbin:/android/sbin:/android/bin", rc.exports["PATH"])
        assertEquals("/android/lib", rc.exports["LD_LIBRARY_PATH"])
        assertEquals("1", rc.exports["ANDROID_BOOTLOGO"])
    }

    @Test fun entriesKeepTheirArgumentsInOrderInBothForms() {
        val rc = LegacyFirmware.parseInitRc(oldRc)
        assertEquals(listOf("reboot_keys", "sdcard_2", "rfcomm0_prep_2"), rc.startup.map { it.name })
        assertEquals(listOf("/sbin/mount", "-t", "vfat", "/dev/mmcblk0", "/sdcard"), rc.startup[1].argv)
        // `args { N value }` block, with trailing comments cut off
        assertEquals(listOf("/sbin/setkey", "-t 0", "-k 0xe5"), rc.startup[0].argv)
        // a quoted argument loses its quotes and keeps its space
        assertEquals(listOf("/android/bin/sdptool", "setattr", "Serial Port 0"), rc.startup[2].argv)
        assertEquals(listOf("/android/bin/hcid", "-n", "-f", "/etc/hcid.conf"), rc.services.first { it.name == "hcid" }.argv)
    }

    @Test fun autostartAndConsoleFlags() {
        val rc = LegacyFirmware.parseInitRc(oldRc)
        assertFalse(rc.startup.first { it.name == "rfcomm0_prep_2" }.autostart)
        assertFalse(rc.services.first { it.name == "hcid" }.autostart)
        assertTrue(rc.services.first { it.name == "runtime" }.autostart)   // "#autostart 0" is a comment
        assertTrue(rc.services.first { it.name == "console" }.console)
        assertFalse(rc.services.first { it.name == "runtime" }.console)
    }

    @Test fun onlyTheRuntimeIsStarted() = tree { root ->
        legacySystem(root)
        File(root, "system/bin/logd").writeText("x"); File(root, "system/bin/hcid").writeText("x")
        val svc = LegacyFirmware.services(LegacyFirmware.parseInitRc(oldRc), root)
        assertEquals(listOf("runtime"), svc.map { it.name })
        assertEquals(listOf("/android/bin/runtime"), svc.single().argv)
    }

    @Test fun aBareSystemImageStillGetsTheRuntime() = tree { root ->
        legacySystem(root)
        assertEquals(listOf("runtime"), LegacyFirmware.services(null, root).map { it.name })
    }

    @Test fun noRuntimeNoService() = tree { root ->
        File(root, "system/bin").mkdirs()
        assertTrue(LegacyFirmware.services(LegacyFirmware.parseInitRc(oldRc), root).isEmpty())
    }

    @Test fun androidPathsResolveIntoSystem() = tree { root ->
        assertEquals(File(root, "system/bin/runtime"), LegacyFirmware.hostFile(root, "/android/bin/runtime"))
        assertEquals(File(root, "sbin/sh"), LegacyFirmware.hostFile(root, "/sbin/sh"))
    }

    // ---- layout

    @Test fun javalibAndAndroidAreLinked() = tree { root ->
        legacySystem(root)
        assertEquals("javalib", LegacyFirmware.frameworkDirName(root))
        assertTrue(LegacyFirmware.isLegacyLayout(root))
        val made = LegacyFirmware.ensureLayout(root)
        assertEquals(2, made.size)
        assertEquals("javalib", Files.readSymbolicLink(File(root, "system/framework").toPath()).toString())
        assertEquals("system", Files.readSymbolicLink(File(root, "android").toPath()).toString())
        assertTrue(File(root, "system/framework/framework.jar").isFile)
        assertTrue(File(root, "android/bin/runtime").isFile)
        assertEquals("framework", LegacyFirmware.frameworkDirName(root))
        // running it again changes nothing
        assertTrue(LegacyFirmware.ensureLayout(root).isEmpty())
    }

    @Test fun existingFrameworkAndAndroidAreNeverReplaced() = tree { root ->
        legacySystem(root)
        File(root, "system/framework").mkdirs()
        File(root, "system/framework/keep").writeText("1")
        File(root, "android").mkdirs()
        File(root, "android/mine").writeText("1")            // a directory with a file in it is not a mount point
        assertTrue(LegacyFirmware.ensureLayout(root).isEmpty())
        assertTrue(File(root, "system/framework/keep").isFile)
        assertTrue(File(root, "android/mine").isFile)
        assertFalse(Files.isSymbolicLink(File(root, "android").toPath()))
    }

    // the ramdisk of build 20645 carries /android as an empty directory, which used to stop the link: every program of the
    // image then failed with "Could not open '/android/bin/linker'"
    @Test fun anEmptyAndroidMountPointGivesWayToTheLink() = tree { root ->
        legacySystem(root)
        File(root, "android").mkdirs()
        val made = LegacyFirmware.ensureLayout(root)
        assertTrue(made.contains("android -> system"))
        assertEquals("system", Files.readSymbolicLink(File(root, "android").toPath()).toString())
        assertTrue(File(root, "android/bin/runtime").isFile)
    }

    @Test fun nestedEmptyDirectoriesAreStillAMountPoint() = tree { root ->
        legacySystem(root)
        File(root, "android/bin").mkdirs()
        LegacyFirmware.ensureLayout(root)
        assertTrue(Files.isSymbolicLink(File(root, "android").toPath()))
    }

    @Test fun aLinkInsideAnAndroidDirectoryIsSomethingInIt() = tree { root ->
        legacySystem(root)
        File(root, "android").mkdirs()
        Files.createSymbolicLink(File(root, "android/x").toPath(), java.nio.file.Paths.get("nowhere"))
        assertTrue(LegacyFirmware.ensureLayout(root).isEmpty())
        assertFalse(Files.isSymbolicLink(File(root, "android").toPath()))
    }

    @Test fun anEmptyAndroidDirectoryOfAModernTreeIsLeftAlone() = tree { root ->
        File(root, "system/framework").mkdirs()
        File(root, "system/bin").mkdirs()
        File(root, "system/bin/app_process").writeBytes("/system/bin/linker".toByteArray())
        File(root, "android").mkdirs()
        assertTrue(LegacyFirmware.ensureLayout(root).isEmpty())
        assertFalse(Files.isSymbolicLink(File(root, "android").toPath()))
    }

    // ---- the framebuffer name

    @Test fun aLegacyTreeGetsDevFb0() = tree { root ->
        legacySystem(root)
        File(root, "dev/graphics").mkdirs()
        File(root, "dev/graphics/fb0").writeBytes(ByteArray(16))
        assertTrue(LegacyFirmware.ensureFbAlias(root))
        assertEquals("graphics/fb0", Files.readSymbolicLink(File(root, "dev/fb0").toPath()).toString())
        assertTrue(File(root, "dev/fb0").canonicalPath.endsWith("dev/graphics/fb0"))
        assertFalse(LegacyFirmware.ensureFbAlias(root))     // once
    }

    @Test fun anExistingDevFb0IsKept() = tree { root ->
        legacySystem(root)
        File(root, "dev").mkdirs()
        File(root, "dev/fb0").writeText("mine")
        assertFalse(LegacyFirmware.ensureFbAlias(root))
        assertEquals("mine", File(root, "dev/fb0").readText())
    }

    @Test fun aModernTreeGetsNoDevFb0() = tree { root ->
        File(root, "system/framework").mkdirs()
        File(root, "system/bin").mkdirs()
        File(root, "system/bin/app_process").writeBytes("/system/bin/linker".toByteArray())
        assertFalse(LegacyFirmware.ensureFbAlias(root))
        assertFalse(File(root, "dev/fb0").exists())
    }

    // ---- the service list

    @Test fun theRuntimeIsSupervisedAsTheSystemService() = tree { root ->
        legacySystem(root)
        val plan = LegacyFirmware.plan(LegacyFirmware.parseInitRc(oldRc), root)
        assertEquals(listOf("zygote"), plan.map { it.name })
        assertEquals(listOf("/android/bin/runtime"), plan.single().argv)
    }

    @Test fun bootPlanReadsTheTreesOwnInitRc() = tree { root ->
        legacySystem(root)
        File(root, "etc").mkdirs()
        File(root, "etc/init.rc").writeText(oldRc)
        assertEquals(listOf("/android/bin/runtime"), LegacyFirmware.bootPlan(root)!!.single().argv)
    }

    @Test fun bootPlanOfABareTreeIsTheDefaultRuntime() = tree { root ->
        legacySystem(root)
        assertEquals(listOf("/android/bin/runtime"), LegacyFirmware.bootPlan(root)!!.single().argv)
    }

    @Test fun bootPlanIsNullForEveryOtherTree() = tree { root ->
        File(root, "system/framework").mkdirs()
        File(root, "system/bin").mkdirs()
        File(root, "system/bin/app_process").writeBytes("/system/bin/linker".toByteArray())
        File(root, "init.rc").writeText(modernRc)
        assertNull(LegacyFirmware.bootPlan(root))
        // a runtime linked against /system (not /android) is no pre-1.0 either
        File(root, "system/bin/runtime").writeBytes(ByteArray(64) + "/system/bin/linker".toByteArray())
        assertNull(LegacyFirmware.bootPlan(root))
    }

    @Test fun aModernTreeIsLeftAlone() = tree { root ->
        File(root, "system/framework").mkdirs()
        File(root, "system/bin").mkdirs()
        File(root, "system/bin/app_process").writeBytes("/system/bin/linker".toByteArray())
        assertFalse(LegacyFirmware.isLegacyLayout(root))
        assertTrue(LegacyFirmware.ensureLayout(root).isEmpty())
        assertFalse(File(root, "android").exists())
    }

    @Test fun aRuntimeLinkedAgainstSystemNeedsNoAndroidAlias() = tree { root ->
        File(root, "system/bin").mkdirs()
        File(root, "system/bin/runtime").writeBytes(ByteArray(64) + "/system/bin/linker".toByteArray())
        assertFalse(LegacyFirmware.isLegacyLayout(root))
    }

    @Test fun noTreeNoFramework() = tree { root -> assertNull(LegacyFirmware.frameworkDirName(root)) }

    // ---- build.prop

    /** The real build.prop of build 20645, as the property parser gives it: no version, no product, two empty values. */
    private val sparse = linkedMapOf(
        "ro.build.id" to "htc-20645.0.8.0.0", "ro.build.date" to "Tue May 15 17:46:51 CST 2007",
        "ro.build.date.utc" to "1179222411", "ro.build.type" to "", "ro.build.product" to "",
        "ro.build.user" to "tony", "ro.build.host" to "tony-ubuntu",
    )

    @Test fun aSparseOldBuildPropGetsAVersionAndAProduct() = tree { root ->
        legacySystem(root)
        val p = LegacyFirmware.completeBuildProps(sparse, root)
        assertEquals("1", p["ro.build.version.sdk"])
        assertEquals("0.9", p["ro.build.version.release"])
        assertEquals("20645", p["ro.build.version.incremental"])
        assertEquals("HTC", p["ro.product.brand"])
        assertEquals("Pre-1.0 build 20645", p["ro.product.model"])
        assertEquals("eng", p["ro.build.type"])            // an empty value counts as missing
        assertEquals("generic", p["ro.build.product"])
    }

    @Test fun whatTheFirmwareSaysIsKept() = tree { root ->
        legacySystem(root)
        val p = LegacyFirmware.completeBuildProps(sparse + mapOf("ro.product.model" to "Sooner", "ro.build.type" to "user"), root)
        assertEquals("Sooner", p["ro.product.model"])
        assertEquals("user", p["ro.build.type"])
        assertEquals("tony", p["ro.build.user"])
        assertEquals("htc-20645.0.8.0.0", p["ro.build.id"])
    }

    @Test fun anOldDateAloneMakesItAPreRelease() = tree { root ->
        // no javalib, no runtime: a 2007 date and no version is still older than 1.0
        val p = LegacyFirmware.completeBuildProps(mapOf("ro.build.id" to "x", "ro.build.date.utc" to "1179222411"), root)
        assertEquals("1", p["ro.build.version.sdk"])
    }

    @Test fun aBuildPropWithAVersionIsNotTouched() = tree { root ->
        legacySystem(root)
        val given = mapOf("ro.build.version.sdk" to "1", "ro.build.version.release" to "1.0", "ro.build.id" to "0.9_r1")
        assertEquals(given, LegacyFirmware.completeBuildProps(given, root))
    }

    @Test fun aModernBuildPropWithoutAVersionIsNotTouched() = tree { root ->
        File(root, "system/framework").mkdirs()
        val given = mapOf("ro.build.date.utc" to "1700000000", "ro.product.model" to "Pixel")
        assertEquals(given, LegacyFirmware.completeBuildProps(given, root))
    }

    @Test fun aGarbledDateIsNotAPreRelease() = tree { root ->
        File(root, "system/framework").mkdirs()
        assertFalse(LegacyFirmware.isPreRelease(mapOf("ro.build.date.utc" to "not a number"), root))
        assertFalse(LegacyFirmware.isPreRelease(emptyMap(), root))
    }
}
