package app.aemu.core

import android.content.Context
import java.io.InputStream

/**
 * The engines' guest files (assets/engines/...) as the APK carries them: every kernel has its own copy of most of
 * them, 473 MB stored uncompressed, 110 MB of it different. The build (app/build.gradle.kts, packEngines) keeps each
 * content once, compressed, under assets/blobs/<sha1>, and writes assets/engines.idx: "path<TAB>sha1<TAB>size" per
 * file. Paths here are the old ones ("engines/kk/libGLES.so").
 *
 * A layer directory (engines/n64-<api>, -own, -gsi, n64-gl) may be split by architecture: next to its plain files it
 * can have "arm32/" and "arm64/" subdirectories with files named the same way ("a#b#c" for a/b/c). Files with
 * different names are all installed; where both carry the same name (the same path in the guest) the guest's
 * architecture decides, see [layerFiles].
 */
object EngineAssets {
    private class Entry(val blob: String, val size: Long)

    @Volatile private var index: Map<String, Entry>? = null
    @Volatile private var dirs: Map<String, List<String>>? = null

    private fun load(ctx: Context): Map<String, Entry> {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val m = HashMap<String, Entry>(4096)
            val d = HashMap<String, MutableList<String>>()
            ctx.assets.open("engines.idx").bufferedReader().useLines { lines ->
                for (l in lines) {
                    val p = l.split('\t')
                    if (p.size < 3) continue
                    m[p[0]] = Entry(p[1], p[2].toLong())
                    d.getOrPut(p[0].substringBeforeLast('/')) { ArrayList() }.add(p[0].substringAfterLast('/'))
                }
            }
            dirs = d
            index = m
            return m
        }
    }

    /** names in a directory, like AssetManager.list (empty when there is none) */
    fun list(ctx: Context, dir: String): Array<String> {
        load(ctx)
        return dirs?.get(dir.trimEnd('/'))?.toTypedArray() ?: emptyArray()
    }

    const val ARM32 = "arm32"
    const val ARM64 = "arm64"

    /**
     * File name to asset path for one layer directory, resolved for a guest of the given architecture. Later wins:
     * the plain files of the directory, then the other architecture's subdirectory, then the guest's own one. A name
     * both subdirectories (or one of them and the plain files) carry is therefore taken from the guest's own.
     */
    fun layerFiles(ctx: Context, dir: String, guest64: Boolean): Map<String, String> {
        load(ctx)
        val d = dir.trimEnd('/')
        val own = if (guest64) ARM64 else ARM32
        val other = if (guest64) ARM32 else ARM64
        val out = LinkedHashMap<String, String>()
        for (sub in listOf(d, "$d/$other", "$d/$own")) for (n in dirs?.get(sub).orEmpty()) out[n] = "$sub/$n"
        return out
    }

    /** every file name a layer directory ships, whatever the architecture */
    fun layerNames(ctx: Context, dir: String): Set<String> {
        load(ctx)
        val d = dir.trimEnd('/')
        return listOf(d, "$d/$ARM32", "$d/$ARM64").flatMap { dirs?.get(it).orEmpty() }.toSet()
    }

    /** the asset path of one file of a layer directory: [prefer]'s subdirectory first, then the other one, then the plain files; null if none */
    fun layerAsset(ctx: Context, dir: String, name: String, prefer: String): String? {
        val d = dir.trimEnd('/')
        val other = if (prefer == ARM64) ARM32 else ARM64
        return listOf("$d/$prefer/$name", "$d/$other/$name", "$d/$name").firstOrNull { exists(ctx, it) }
    }

    fun exists(ctx: Context, path: String): Boolean = load(ctx).containsKey(path)

    /** size in bytes, -1 when there is no such file */
    fun size(ctx: Context, path: String): Long = load(ctx)[path]?.size ?: -1L

    fun open(ctx: Context, path: String): InputStream {
        val e = load(ctx)[path] ?: throw java.io.FileNotFoundException(path)
        return ctx.assets.open("blobs/" + e.blob)
    }
}
