/* Added for AEmulator Plus, 2026-11-06: choosing between several images of the same partition in one import.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

/**
 * An archive (zip, 7z, rar, tar, cpio) may carry the same partition more than once: `system_a.img`, `system_b.img`, `system.tar` and a
 * `system/` folder, `boot_a.img` and `boot_b.img`. Before anything is written, [Importer] asks which one to take. This file holds the parts of
 * that which need no Android: which names belong to which partition, what a build.prop says about a partition, and the
 * text of each option.
 */

/** What a partition says about itself in its build.prop. Every part may be missing; a partition without any (boot) has no identity. */
data class BuildIdentity(val device: String?, val api: String?, val build: String?) {
    val isEmpty: Boolean get() = device == null && api == null && build == null
}

/** One question: which of [options] becomes [partition]. [index] of [total] counts the questions of this import. */
class PartitionPrompt(val partition: String, val index: Int, val total: Int, val options: List<String>)

/** Answers a [PartitionPrompt] with the position of the chosen option, or [CANCEL] to stop the import. Called on the import thread. */
fun interface PartitionChooser {
    fun choose(prompt: PartitionPrompt): Int

    companion object { const val CANCEL = -1 }
}

internal object PartitionChoice {
    enum class Kind { IMAGE, TAR, FOLDER }

    /** Groups in the order they are asked about. Skin partitions (mi_ext, my_*, ...) follow, alphabetically. */
    private val GROUP_ORDER = listOf("system", "vendor", "product", "system_ext", "odm", "oem", "boot", "recovery")

    fun groupRank(group: String): Int = GROUP_ORDER.indexOf(group).let { if (it < 0) GROUP_ORDER.size else it }

    private val SLOT = Regex("(?i)_[ab](?=\\.|$)")

    /** The slot of a name: 'a' or 'b', or null for a name without one. */
    fun slotOf(base: String): Char? = SLOT.find(base)?.value?.get(1)?.lowercaseChar()

    /** "system_b.img" -> "system.img": the name the existing matchers in [PartitionNames] know. */
    fun withoutSlot(base: String): String = SLOT.replaceFirst(base, "")

    /**
     * The partition an image file holds, judged by its name alone, or null. Slot-less and both slots count; this is the
     * same set of names [PartitionNames] accepts, plus the "_b" copies it deliberately ignores when there is nothing to choose.
     * [lz4Names]: "system.img.lz4" counts as "system.img" (the tar reader unpacks those; the other archive readers do not).
     */
    fun imageGroup(base: String, bareOk: Boolean, lz4Names: Boolean = false): String? {
        val stripped = PartitionNames.stripOdinMd5(base)
        // "system_b.img" is known as "system.img"; "system_b.bin" only as "system_a.bin" (the matchers take a bare name
        // for .img, the slot-"a" name for .bin): so try the name without its slot, then with the slot turned to "a"
        return groupOf(withoutSlot(stripped), bareOk, lz4Names) ?: groupOf(asSlotA(stripped), bareOk, lz4Names)
    }

    private fun groupOf(name: String, bareOk: Boolean, lz4Names: Boolean): String? {
        val n = if (lz4Names) LZ4.replaceFirst(name, "") else name
        return when {
            PartitionNames.isSystem(n, bareOk) -> "system"
            PartitionNames.isVendor(n, bareOk) -> "vendor"
            PartitionNames.isOem(n, bareOk) -> "oem"
            PartitionNames.isBoot(n, bareOk) -> "boot"
            PartitionNames.isRecovery(n, bareOk) -> "recovery"
            else -> PartitionNames.extraMount(n, bareOk)?.substringAfterLast('/')
        }
    }

    /** "system_b.bin" -> "system_a.bin": the same partition under the one slot name the matchers accept for every extension. */
    fun asSlotA(base: String): String = SLOT.replaceFirst(base, "_a")

    /**
     * The name a picked image is imported under: one the importer's own matchers accept. "system_b.img" becomes "system.img",
     * "system_b.bin" becomes "system_a.bin" (there is no slot-less .bin name).
     */
    fun importName(base: String, bareOk: Boolean, lz4Names: Boolean = false): String {
        if (slotOf(base) == null) return base
        val plain = withoutSlot(base)
        return if (groupOf(withoutSlot(PartitionNames.stripOdinMd5(base)), bareOk, lz4Names) != null) plain else asSlotA(base)
    }

    private val LZ4 = Regex("(?i)\\.lz4$")

    private val TAR = Regex("(?i)(.+?)\\.(tar|tar\\.md5|tar\\.gz|tgz)")

    /**
     * `system.tar`, `vendor_a.tar.md5`, `system_ext.tgz`, ...: a tar holding one partition, named like the partition's image
     * (its files, or an image of it). A package's own tars (`AP_G900F.tar.md5`, `CSC_...`) are named after no partition: null.
     */
    fun tarGroup(base: String): String? {
        val stem = TAR.matchEntire(base)?.groupValues?.get(1) ?: return null
        return imageGroup("$stem.img", bareOk = false)
    }

    /**
     * Whether a tar of [group] carries the partition's files: vendor.tar, product.tar, system_ext.tar, odm.tar, oem.tar and the
     * skins' partitions. A system tar holds system/ files (or an image), a boot or recovery tar an image: the importer's other
     * rules read those.
     */
    fun takesFiles(group: String): Boolean = group != "system" && group != "boot" && group != "recovery"

    /**
     * Where a file of a partition tar belongs in the tree. The tar may carry the partition folder ("system_ext/lib/x",
     * "system/system_ext/lib/x") or the partition's own top level ("lib/x"): both end up under [mountOf].
     */
    fun tarPath(group: String, name: String): String {
        val mount = mountOf(group)
        val p = name.removePrefix("./").trim('/')
        if (p == group || p == mount) return mount
        val prefix = listOf("$group/", "$mount/").firstOrNull { p.startsWith(it) }
        return mount + "/" + (if (prefix != null) p.removePrefix(prefix) else p)
    }

    /** Where the importer files a partition's contents: product and system_ext live under system/. */
    fun mountOf(group: String): String = if (group == "product" || group == "system_ext") "system/$group" else group

    // ---------------------------------------------------------------- build.prop

    /** Places a partition's build.prop can be: the partition's top level, a system-as-root image's system/ folder, etc/. */
    val BUILD_PROP_PATHS = listOf("build.prop", "system/build.prop", "etc/build.prop")

    /** Most of a build.prop that is read: they are a few tens of KB, anything bigger is not one. */
    const val MAX_PROP_BYTES = 1 shl 20

    /**
     * Device, API level and build number of [group] from the properties of its build.prop. Device and API follow the rules
     * [Analyzer] uses for the machine's name and version, so what the prompt shows is what the library shows afterwards.
     * Partitions other than system carry the same values under their own names (`ro.vendor.build.id`,
     * `ro.product.vendor.model`, ...), which are tried first for them.
     */
    fun identity(group: String, p: Map<String, String>): BuildIdentity {
        fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> p[k]?.trim()?.takeIf { it.isNotEmpty() } }
        val system = group == "system"

        val brand = first("ro.product.brand", "ro.product.$group.brand", "ro.product.manufacturer", "ro.product.$group.manufacturer")
            ?.replaceFirstChar { it.uppercase() }
        val model = first("ro.product.model", "ro.product.$group.model", "ro.product.device", "ro.product.$group.device")
        val device = when {
            model == null -> null
            brand == null || model.startsWith(brand, ignoreCase = true) -> model
            else -> "$brand $model"
        }

        val sdkKeys = if (system) arrayOf("ro.build.version.sdk", "ro.system.build.version.sdk")
            else arrayOf("ro.$group.build.version.sdk", "ro.build.version.sdk")
        val sdk = first(*sdkKeys)?.toIntOrNull()
        val release = first("ro.build.version.release", "ro.$group.build.version.release")
        val codename = first("ro.build.version.codename")
        val api = if (sdk == null && release == null) null
            else Analyzer.effectiveApi(sdk, codename, release).toString()
        // Android 16 QPR2 and later: a minor SDK number ("36.1"); the property is missing, or 0, on a major release
        val minor = if (system) first("ro.build.version.sdk_minor")?.toIntOrNull()?.takeIf { it > 0 } else null
        val apiText = if (api != null && minor != null) "$api.$minor" else api

        val buildKeys = if (system) arrayOf("ro.build.id", "ro.system.build.id", "ro.build.display.id")
            else arrayOf("ro.$group.build.id", "ro.build.id", "ro.build.display.id")
        return BuildIdentity(device, apiText, first(*buildKeys))
    }

    // ---------------------------------------------------------------- options

    /**
     * "system_a.img: Galaxy S5, API level 28, build number PPTA.190909.001". A partition that says nothing about itself
     * is just its [label] ("boot_a.img"); a build.prop with only some of the three keeps the ones it has.
     */
    fun line(label: String, id: BuildIdentity?, apiText: (String) -> String, buildText: (String) -> String): String {
        if (id == null || id.isEmpty) return label
        val parts = listOfNotNull(id.device, id.api?.let(apiText), id.build?.let(buildText))
        return "$label: ${parts.joinToString(", ")}"
    }

    /** Presentation order of one partition's options: images, then tars, then the folder; "_a" and slot-less before "_b". */
    fun optionOrder(kind: Kind, slot: Char?): Int = kind.ordinal * 10 + when (slot) { 'a' -> 0; null -> 1; else -> 2 }

    /** The first [len] bytes of [head] are all zero: an erased or never-written partition. */
    fun isBlank(head: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (head[i].toInt() != 0) return false
        return true
    }
}
