package app.aemu.importer

/**
 * Recognises the split-sparse `system` naming schemes found in vendor firmware and puts the
 * chunks in the right order:
 *
 *   system.img_sparsechunk.N   system.img_sparsechunkN   system_sparsechunk.N  system_sparsechunkN
 *   system.img.N               system.imgN               system.N              system_N
 *
 * N is any number (compared numerically, so 10 comes after 2). The first chunk is 0 or 1
 * depending on what the archive has — nothing is assumed, the chunks are simply sorted by N, so
 * a set that starts at 0 and a set that starts at 1 both work, as does a set with gaps.
 */
object SparseChunks {
    private val NAME = Regex("(?i)(system(?:(?:\\.img)?_sparsechunk\\.?|\\.img\\.?|[._]))(\\d{1,9})")

    class Chunk(val path: String, val index: Int)

    /** Index of [path] if its file name is one of the chunk variants, else null. */
    fun indexOf(path: String): Int? = NAME.matchEntire(baseName(path))?.groupValues?.get(2)?.toIntOrNull()

    fun isChunkName(path: String) = indexOf(path) != null

    private fun baseName(path: String) = path.replace('\\', '/').substringAfterLast('/')

    /**
     * Picks the chunk set out of [paths]: chunks are grouped by folder and naming scheme (so
     * `system.0`/`system.1` don't mix with `system.img.0`), the largest complete-looking group
     * wins (ties: the shallowest folder, then the first name), and the result is sorted by N.
     * Returns an empty list when there is no chunk — a lone `system.0` is not treated as a set
     * unless it is the only candidate, which the caller verifies by the sparse magic anyway.
     */
    fun select(paths: Collection<String>): List<String> {
        val groups = LinkedHashMap<String, MutableList<Chunk>>()
        for (p in paths) {
            val norm = p.replace('\\', '/')
            val m = NAME.matchEntire(norm.substringAfterLast('/')) ?: continue
            val idx = m.groupValues[2].toIntOrNull() ?: continue
            val key = norm.substringBeforeLast('/', "") + "/" + m.groupValues[1].lowercase()
            groups.getOrPut(key) { ArrayList() }.add(Chunk(p, idx))
        }
        val best = groups.entries.minWithOrNull(
            compareBy<Map.Entry<String, MutableList<Chunk>>>({ -it.value.size },
                { it.key.substringBeforeLast('/').count { c -> c == '/' } }, { it.key })
        ) ?: return emptyList()
        // the same index twice in one group (e.g. system.1 and system.01): keep the first seen
        return best.value.distinctBy { it.index }.sortedBy { it.index }.map { it.path }
    }
}
