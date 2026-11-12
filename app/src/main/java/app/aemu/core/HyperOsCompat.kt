package app.aemu.core

import java.io.File

/**
 * Added for AEmulator Plus (2026-11-10): SystemUI and the launcher of Xiaomi MIUI / HyperOS firmware.
 *
 * Two things stop the home screen of such a tree on the 64-bit engine, both seen in a HyperOS 3 (Android 17) log:
 *
 *  1. SystemUI (and later the launcher) dies in its RenderThread, SIGSEGV null pointer dereference, in libhwui:
 *     SkRuntimeEffectBuilder::SkRuntimeEffectBuilder <- MiBlurBlendCustom::onMakeCustom <-
 *     MiBackgroundBlurBlend::updateCustomBlurImage <- RenderNodeDrawable::drawContent.
 *     Xiaomi's background blur compiles its own SkSL shader; the effect comes back null on this GPU path and the
 *     builder is made from it without a check. The process is persistent, so the system restarts it and it dies
 *     again, the launcher never gets a window. The Java side only asks for the blur when the "background blur"
 *     properties say the device supports it, so those are turned off (and pinned: init scripts set them again).
 *
 *  2. The launcher (com.miui.home, also com.miui.weather2) is an app of the "Rust runtime": the system starts it through
 *     the hyos_spawner service ("pre startProcess com.miui.home ... rust:true"), not through zygote. The plan here started
 *     no such service, so "RustProcessImpl: IOException while communicating with spawner - No such file or directory",
 *     pid 0, "failed to attach". (A first try turned the runtime off by property: the system then did fall back to zygote,
 *     but the package has no code of its own, "DexPathList[[]" and "Unable to get provider com.miui.maml.MamlProvider",
 *     and the launcher died in a loop. Its code is only loaded by the spawner.)
 *     init.hyos_spawner.rc starts hyos_spawner when zygote runs and rust.runtime_active=1; InitPlan starts it after zygote
 *     (it owns the "hyos_spawner" socket), and the properties below are what that rc file looks at.
 *     The rustruntime_cfg*.xml stays where it is: it declares the hyperos.rustruntime.v3/v5 shared libraries the packages
 *     require (see TreeFixer.parkRustRuntimeConfig).
 */
internal object HyperOsCompat {
    /** Xiaomi's background blur: off, so nothing builds the custom blur shader */
    val BLUR_PROPS: Map<String, String> = linkedMapOf(
        "persist.sys.background_blur_supported" to "false",
        "persist.sys.background_blur_status_default" to "false",
        "persist.sys.background_blur_setting_enabled" to "false",
        "persist.sys.background_blur_version" to "0",
        "persist.sys.add_blurenable" to "false",
        "persist.sys.advanced_visual_release" to "0",
        "ro.miui.backdrop_sampling_enabled" to "false",
    )

    /** the Rust runtime: on, as the firmware's own rc file expects it (InitPlan starts hyos_spawner) */
    val RUST_PROPS: Map<String, String> = linkedMapOf(
        "persist.sys.rust_runtime_active" to "1",
        "rust.runtime_active" to "1",
    )

    private val FRAMEWORK_DIRS = listOf("system_ext/framework", "system/system_ext/framework", "system/framework", "product/framework")

    /** a MIUI / HyperOS tree: it has miui-framework.jar */
    fun isMiui(root: File): Boolean = FRAMEWORK_DIRS.any { File(root, "$it/miui-framework.jar").isFile }

    /** the tree has the Rust runtime: its spawner rc or its shared-library declaration */
    fun hasRustRuntime(root: File): Boolean {
        val rc = listOf("system_ext/etc/init", "system/system_ext/etc/init").any { File(root, "$it/init.hyos_spawner.rc").isFile }
        if (rc) return true
        return listOf("product/etc/permissions", "system/product/etc/permissions", "system_ext/etc/permissions", "system/system_ext/etc/permissions", "system/etc/permissions")
            .any { d -> File(root, d).listFiles().orEmpty().any { it.name.startsWith("rustruntime_cfg") && it.name.endsWith(".xml") } }
    }

    /**
     * Properties to force on this tree. Only the 64-bit engine on Android 10+ (hwui over GLES, the case above); trees
     * that are not MIUI/HyperOS get nothing.
     */
    fun overrides(root: File, api: Int, n64: Boolean): Map<String, String> {
        if (!n64 || api < 29 || !isMiui(root)) return emptyMap()
        val m = LinkedHashMap<String, String>(BLUR_PROPS)
        if (hasRustRuntime(root)) m.putAll(RUST_PROPS)
        return m
    }
}
