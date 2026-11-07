/* Added for AEmulator Plus, 2026-11-01: user-arranged library (order + folders). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.aemu.AppPrefs
import app.aemu.core.GuestImage
import org.json.JSONArray
import org.json.JSONObject

/** A folder of ROMs: [roms] are image ids in display order. Folders are never nested. */
internal data class LibFolder(val name: String, val open: Boolean, val roms: List<String>)

/**
 * How the user arranged the library, shared by the legacy list and the 0.0.0.3 grid (the 3D library ignores it).
 * [top] is the top level in display order: image ids and folder ids ("folder:…") mixed; [folders] holds the contents.
 * Every function returns a new layout; nothing here touches the images themselves.
 */
internal data class LibLayout(
    val top: List<String> = emptyList(),
    val folders: Map<String, LibFolder> = emptyMap(),
) {
    fun isFolder(key: String) = key in folders

    /** The folder that holds this ROM, or null when it sits on the top level. */
    fun folderOf(rom: String): String? = folders.entries.firstOrNull { rom in it.value.roms }?.key

    /** The top-level entry that [key] belongs to: the key itself, or the folder it is in. */
    fun entryOf(key: String): String? = if (key in top) key else folderOf(key)

    /** Position of a ROM inside whatever holds it (its folder, or the top level). */
    fun indexIn(rom: String): Int = folderOf(rom)?.let { folders.getValue(it).roms.indexOf(rom) } ?: top.indexOf(rom)

    /** Drops ROMs that no longer exist; ROMs that are not placed yet (new imports) go to the top, newest first. */
    fun synced(images: List<GuestImage>): LibLayout {
        val ids = images.map { it.id }.toSet()
        val keptTop = top.filter { it in ids || it in folders }
        val keptFolders = folders.mapValues { (_, f) -> f.copy(roms = f.roms.filter { it in ids }) }
        val known = HashSet<String>(keptTop)
        for (f in keptFolders.values) known.addAll(f.roms)
        val fresh = images.map { it.id }.filter { it !in known }
        return LibLayout(fresh + keptTop, keptFolders)
    }

    private fun withoutRom(id: String): LibLayout = copy(
        top = top.filter { it != id },
        folders = folders.mapValues { (_, f) -> if (id in f.roms) f.copy(roms = f.roms.filter { it != id }) else f },
    )

    private fun insertTop(id: String, at: Int): LibLayout =
        copy(top = top.toMutableList().also { it.add(at.coerceIn(0, it.size), id) })

    private fun insertIn(folder: String, id: String, at: Int): LibLayout {
        val f = folders[folder] ?: return insertTop(id, top.size)
        val roms = f.roms.toMutableList().also { it.add(at.coerceIn(0, it.size), id) }
        return copy(folders = folders + (folder to f.copy(roms = roms)))
    }

    /** Puts [rom] right before or after [target] (another ROM), in the target's folder or on the top level. */
    fun placeRom(rom: String, target: String, after: Boolean): LibLayout {
        if (rom == target) return this
        val base = withoutRom(rom)
        val tf = base.folderOf(target)
        if (tf == null) {
            val i = base.top.indexOf(target)
            return if (i < 0) this else base.insertTop(rom, if (after) i + 1 else i)
        }
        val i = base.folders.getValue(tf).roms.indexOf(target)
        return base.insertIn(tf, rom, if (after) i + 1 else i)
    }

    /** Takes [rom] out of its folder (if any) and puts it on the top level, just before [folder]. */
    fun placeRomBeforeFolder(rom: String, folder: String): LibLayout {
        val base = withoutRom(rom)
        val i = base.top.indexOf(folder)
        return if (i < 0) this else base.insertTop(rom, i)
    }

    /** Puts [rom] into [folder]: first when the folder is open (it shows right under the header), last when closed. */
    fun moveRomToFolder(rom: String, folder: String): LibLayout {
        val f = folders[folder] ?: return this
        return withoutRom(rom).insertIn(folder, rom, if (f.open) 0 else Int.MAX_VALUE)
    }

    /** Takes [rom] out of its folder and puts it on the top level right after it. */
    fun takeOut(rom: String): LibLayout {
        val f = folderOf(rom) ?: return this
        val base = withoutRom(rom)
        return base.insertTop(rom, base.top.indexOf(f) + 1)
    }

    /** Moves a folder next to another top-level entry ([target] is a top-level key: a ROM or a folder). */
    fun placeFolder(folder: String, target: String, after: Boolean): LibLayout {
        if (folder == target) return this
        val base = copy(top = top.filter { it != folder })
        val i = base.top.indexOf(target)
        return if (i < 0) this else base.insertTop(folder, if (after) i + 1 else i)
    }

    /** A new, open folder at the top of the library; [rom] (if given) goes into it. */
    fun createFolder(id: String, name: String, rom: String? = null): LibLayout {
        val l = copy(top = listOf(id) + top, folders = folders + (id to LibFolder(name, true, emptyList())))
        return if (rom != null) l.moveRomToFolder(rom, id) else l
    }

    fun renameFolder(id: String, name: String): LibLayout {
        val f = folders[id] ?: return this
        return copy(folders = folders + (id to f.copy(name = name)))
    }

    fun setOpen(id: String, open: Boolean): LibLayout {
        val f = folders[id] ?: return this
        return copy(folders = folders + (id to f.copy(open = open)))
    }

    /** Deletes the folder only; its ROMs stay in the library, in the folder's place. */
    fun deleteFolder(id: String): LibLayout {
        val f = folders[id] ?: return this
        val t = top.toMutableList()
        val i = t.indexOf(id)
        if (i >= 0) { t.removeAt(i); t.addAll(i, f.roms) } else t.addAll(f.roms)
        return LibLayout(t, folders - id)
    }

    companion object {
        /**
         * What the library looks like before the user arranges anything: the order it always had. The classic list
         * used to show imported firmwares first, then the containers made from them; the grid showed newest first.
         */
        fun seed(images: List<GuestImage>, systemsFirst: Boolean): LibLayout {
            val ordered = if (systemsFirst) images.filter { it.baseId.isEmpty() } + images.filter { it.baseId.isNotEmpty() } else images
            return LibLayout(ordered.map { it.id })
        }

        fun newFolderId(): String = "folder:" + java.util.UUID.randomUUID().toString().take(8)
    }
}

/** What the lists draw: a folder header, or a ROM (with the folder it is shown in, when it is in one). */
internal sealed interface LibRow { val key: String }

internal data class FolderRow(val id: String, val folder: LibFolder) : LibRow {
    override val key: String get() = id
}

internal data class RomRow(val img: GuestImage, val folderId: String?) : LibRow {
    override val key: String get() = img.id
}

/** The flat list the library shows. A folder in [collapse] is drawn closed (used while the folder itself is dragged). */
internal fun libraryRows(layout: LibLayout, images: List<GuestImage>, collapse: String? = null): List<LibRow> {
    val byId = images.associateBy { it.id }
    val out = ArrayList<LibRow>()
    for (id in layout.top) {
        val f = layout.folders[id]
        if (f != null) {
            out.add(FolderRow(id, f))
            if (f.open && id != collapse) for (r in f.roms) byId[r]?.let { out.add(RomRow(it, id)) }
        } else {
            byId[id]?.let { out.add(RomRow(it, null)) }
        }
    }
    return out
}

internal object LibraryLayoutStore {
    fun load(ctx: Context): LibLayout? = runCatching {
        AppPrefs.libraryLayout(ctx)?.let { parse(JSONObject(it)) }
    }.getOrNull()

    fun save(ctx: Context, layout: LibLayout) = AppPrefs.setLibraryLayout(ctx, toJson(layout).toString())

    fun toJson(l: LibLayout): JSONObject {
        val folders = JSONObject()
        for ((id, f) in l.folders) {
            folders.put(id, JSONObject().put("name", f.name).put("open", f.open).put("roms", JSONArray(f.roms)))
        }
        return JSONObject().put("top", JSONArray(l.top)).put("folders", folders)
    }

    fun parse(o: JSONObject): LibLayout {
        val top = o.optJSONArray("top")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val folders = LinkedHashMap<String, LibFolder>()
        val fo = o.optJSONObject("folders")
        if (fo != null) {
            val keys = fo.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val f = fo.getJSONObject(id)
                val roms = f.optJSONArray("roms")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
                folders[id] = LibFolder(f.optString("name", ""), f.optBoolean("open", true), roms)
            }
        }
        return LibLayout(top, folders)
    }
}

/**
 * Holds the arrangement for the home screen. Nothing is written until the user actually arranges something, so
 * the library keeps its old order until then. [images] and [systemsFirst] are refreshed on every composition.
 */
@Stable
internal class LibraryArranger(private val ctx: Context) {
    private var saved by mutableStateOf(LibraryLayoutStore.load(ctx))
    var images: List<GuestImage> = emptyList()
    var systemsFirst: Boolean = false

    /** Always the current arrangement, synced with the images that exist right now. */
    fun layout(): LibLayout = saved?.synced(images) ?: LibLayout.seed(images, systemsFirst)

    fun rows(collapse: String? = null): List<LibRow> = libraryRows(layout(), images, collapse)

    /** Changes the arrangement on screen (called repeatedly while dragging). */
    fun update(l: LibLayout) { saved = l }

    /** Writes the arrangement to disk. */
    fun commit() { LibraryLayoutStore.save(ctx, layout()) }
}
