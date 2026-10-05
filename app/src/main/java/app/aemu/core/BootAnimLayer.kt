package app.aemu.core

/** Parses `dumpsys SurfaceFlinger` to find out which layer is on top of the screen. */
internal object BootAnimLayer {
    private val layerLine = Regex("""^\+ \S+(?: 0x[0-9a-fA-F]+)? \((.+)\)\s*$""")

    /** Names of the visible layers, bottom to top (SurfaceFlinger lists them in z order). */
    fun visibleLayers(dump: String): List<String> {
        val out = ArrayList<String>()
        var inSection = false
        for (line in dump.lineSequence()) {
            if (!inSection) { if (line.startsWith("Visible layers")) inSection = true; continue }
            val m = layerLine.matchEntire(line)
            if (m != null) { out += m.groupValues[1]; continue }
            // detail lines are indented; the first unindented non-layer line ends the section
            if (line.isNotEmpty() && line[0] != ' ' && line[0] != '\t' && line[0] != '+') break
        }
        return out
    }

    fun isBootAnim(name: String) = name.contains("BootAnimation", ignoreCase = true) || name.equals("bootanim", ignoreCase = true)

    /** True when the topmost visible layer belongs to the boot animation. */
    fun topIsBootAnim(dump: String): Boolean = visibleLayers(dump).lastOrNull()?.let(::isBootAnim) == true
}
