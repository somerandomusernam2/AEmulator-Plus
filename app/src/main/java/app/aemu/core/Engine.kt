package app.aemu.core

/**
 * Набор движка: хостовые исполняемые файлы (лежат в nativeLibraryDir как lib*.so)
 * и гостевые файлы (32-битный ARM, копируются в дерево прошивки из assets/engines/<id>).
 *
 * GB — Android 2.x (Gingerbread/Froyo): GL рисуется в pbuffer и читается в fb0, binder и GL-сервер
 *      живут в отдельных процессах-«слотах» приложения.
 * KK — Android 4.x и новее: GL-мост работает прямо в процессе приложения и рисует в Surface.
 */
/**
 * A firmware with no 64-bit side at all: no 64-bit libc and no app_process64 (Wear OS and older phone builds, armeabi-v7a).
 * On the native arm64 engine every program of such a firmware goes through qemu-arm, and the engine's own 64-bit
 * files have no 64-bit libraries to run against.
 */
fun isPure32Tree(root: java.io.File): Boolean =
    // (libc, not the directory: the engine makes system/lib64/egl for its own drivers on some releases)
    !java.io.File(root, "system/lib64/libc.so").isFile && !java.io.File(root, "system/bin/app_process64").isFile

/**
 * Added for AEmulator Plus (2026-11-01): a firmware that has a 32-bit side next to its 64-bit one (zygote_secondary,
 * app_process32, the 32-bit libc). A pure 32-bit tree has one too; [isPure32Tree] only tells the two apart.
 * The bundled libqemu_arm.so answers every extended-attribute call with ENOSYS, so the 32-bit programs of such a
 * tree need libaemuxattr.so as well (webview_zygote: "Unable to getfilecon on /data/user: Function not implemented").
 */
fun has32BitSide(root: java.io.File): Boolean =
    java.io.File(root, "system/bin/app_process32").isFile || java.io.File(root, "system/lib/libc.so").isFile ||
        java.io.File(root, "apex/com.android.runtime/lib/bionic/libc.so").isFile

/**
 * true when the program at the guest path [program] is a 32-bit ELF (symlinks inside the tree are followed against
 * the tree's root, not the phone's). A script, a missing file or a 64-bit program: false.
 */
fun isGuestElf32(root: java.io.File, program: String?): Boolean {
    if (program == null) return false
    var f = java.io.File(root, program.removePrefix("/"))
    repeat(8) {
        val p = f.toPath()
        if (!java.nio.file.Files.isSymbolicLink(p)) return runCatching {
            f.inputStream().use { i ->
                val h = ByteArray(5)
                i.read(h) == 5 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() &&
                    h[3] == 'F'.code.toByte() && h[4] == 1.toByte()
            }
        }.getOrDefault(false)
        val t = java.nio.file.Files.readSymbolicLink(p).toString()
        f = if (t.startsWith("/")) java.io.File(root, t.removePrefix("/")) else java.io.File(f.parentFile, t)
    }
    return false
}

enum class Engine(
    val id: String,
    val title: String,
    val qemu: String,
    /** same translator with the guest mmap base moved off 0x40000000 (see [QemuFallback]) */
    val qemuXperiaPlay: String,
    val binderd: String,
    val glserverd: String,
    val runner: String,
    /** гостевая реализация EGL/GLES: имя в assets → имя в /system/lib/egl */
    val guestGles: String,
) {
    GB(
        id = "gb",
        title = "Fallback for 2.x",
        qemu = "libqemu_gb.so",
        qemuXperiaPlay = "libqemu_gb_xperiaplay.so",
        binderd = "libbinderd_gb.so",
        glserverd = "libglserverd_gb.so",
        runner = "libdhdrun_gb.so",
        guestGles = "libGLES_dhd.so",
    ),
    KK(
        id = "kk",
        title = "Universal (2.2–7.x)",
        qemu = "libqemu_kk.so",
        qemuXperiaPlay = "libqemu_kk_xperiaplay.so",
        binderd = "libbinderd_kk.so",
        glserverd = "libglserverd_kk.so",
        runner = "libdhdrun_kk.so",
        guestGles = "libGLES.so",
    ),
    /** arm64 firmware on an arm64 phone: the guest's own code runs natively (native/guest64), no translator. */
    N64(
        id = "n64",
        title = "Native arm64",
        qemu = "",      // per Android generation: see Kernel64
        qemuXperiaPlay = "",
        binderd = "",
        glserverd = "",
        runner = "",
        guestGles = "",
    );

    /**
     * Standalone GL server for this host GPU. On Mali the server must not put an app's ES2+ context into the share
     * group of SurfaceFlinger's ES1 context: the driver then hands the app an ES 1.1 context, no shader compiles and
     * hwui aborts in a loop (5.0+). The *_mali build only shares ES1 contexts with each other.
     */
    fun glserverdFor(gpu: String): String =
        if (this == KK && gpu.contains("mali")) "libglserverd_kk_mali.so" else glserverd

    companion object {
        /**
         * KK — универсальный: GL-мост в приложении (быстрее) и полный GLES 1/2/3, работает и с 2.2.
         * GB остаётся запасным (выбирается в настройках образа).
         */
        fun forApi(api: Int): Engine = KK
        fun byId(id: String?): Engine = entries.firstOrNull { it.id == id } ?: KK
    }
}
