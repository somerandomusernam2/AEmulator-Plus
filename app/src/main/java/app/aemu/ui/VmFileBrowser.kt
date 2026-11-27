/* AEmulator Plus addition, 2026-11-27: import host files into a VM. */
package app.aemu.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aemu.AppPrefs
import app.aemu.R
import app.aemu.core.GuestImage
import app.aemu.core.OpResult
import app.aemu.core.TextPreview
import app.aemu.core.VmFileOps
import app.aemu.core.VmNode
import app.aemu.core.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** The "Browse VM files" button at the bottom of the VM settings. */
@Composable
internal fun VmFilesSection(img: GuestImage, busy: Boolean) {
    var open by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { open = true }) {
        Text(stringResource(R.string.vmf_browse))
    }
    VmHostFileImportSection(img, busy)
    if (open) VmFileBrowserDialog(img) { open = false }
}

private sealed interface VmListing {
    data object Loading : VmListing
    data object Unreadable : VmListing
    class Ok(val nodes: List<VmNode>) : VmListing
}

private enum class Target { INSIDE, OUTSIDE }

/** Folders first, then the files; "system_2" before "system_10". */
private fun nodeComparator(key: SortKey, asc: Boolean): Comparator<VmNode> {
    val byName = Comparator<VmNode> { a, b -> naturalCompare(a.name, b.name) }
    val by: Comparator<VmNode> = when (key) {
        SortKey.NAME -> byName
        SortKey.DATE -> compareBy<VmNode> { it.modified }.thenComparing(byName)
        SortKey.SIZE -> compareBy<VmNode> { it.size }.thenComparing(byName)
    }
    return Comparator { a, b ->
        when {
            a.isDir != b.isDir -> if (a.isDir) -1 else 1
            a.isDir && key == SortKey.SIZE -> byName.compare(a, b)
            asc -> by.compare(a, b)
            else -> by.compare(b, a)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The browser
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Full-screen browser on the VM's directory: the partitions (root/system, root/data, ...) and the image files the export
 * reads from. A tap opens a folder or previews a text file; press and hold (or a tap on a file without preview) selects,
 * and the bar at the bottom then copies, renames or deletes. Changes need every VM to be shut down, like export and reset.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VmFileBrowserDialog(img: GuestImage, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val vm = remember(img.id) { VmFileOps.vmDir(ctx, img.id) }
    val allowed = remember { ctx.filesDir.canonicalFile }

    var current by remember { mutableStateOf(vm) }
    var refresh by remember { mutableStateOf(0) }
    var sortKey by remember { mutableStateOf(SortKey.NAME) }
    var asc by remember { mutableStateOf(true) }
    var hidden by remember { mutableStateOf(true) }
    val selected = remember { mutableStateListOf<VmNode>() }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var preview by remember { mutableStateOf<VmNode?>(null) }
    var renaming by remember { mutableStateOf<VmNode?>(null) }
    var deleting by remember { mutableStateOf<List<VmNode>?>(null) }
    var copying by remember { mutableStateOf<List<VmNode>?>(null) }
    var target by remember { mutableStateOf<Target?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(current) { selected.clear(); searching = false; query = "" }

    fun runOp(label: String, block: () -> String?) {
        working = label
        progress = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            working = null
            val text = r.fold({ it }, { ctx.getString(R.string.vmf_failed, it.userMessage(ctx)) })
            if (text != null) result = text
            selected.clear()
            refresh++
        }
    }
    fun summary(r: OpResult) = ctx.getString(if (r.skipped == 0) R.string.vmf_result_copied else R.string.vmf_result_copied_skipped, r.done, r.skipped)

    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val src = copying?.map { it.file }
        target = null
        if (uri != null && src != null) {
            copying = null
            runOp(ctx.getString(R.string.vmf_working_copy)) { summary(VmFileOps.copyOutToTree(ctx, src, uri) { progress = it }) }
        }
    }

    fun toggle(n: VmNode) {
        val i = selected.indexOfFirst { it.path == n.path }
        if (i >= 0) selected.removeAt(i) else selected.add(n)
    }
    fun up(d: File): File? = if (d == vm) null else d.parentFile
    fun openNode(n: VmNode) {
        when {
            selected.isNotEmpty() -> toggle(n)
            n.isDir -> if (VmFileOps.canEnter(allowed, n.file)) { current = n.file } else { toggle(n) }
            n.isRegular -> if (VmFileOps.isTextName(n.name)) { preview = n } else { scope.launch {
                if (withContext(Dispatchers.IO) { VmFileOps.looksLikeText(n.file) }) { preview = n } else { toggle(n) }
            } }
            else -> toggle(n)
        }
    }

    Dialog(onDismissRequest = {
        if (selected.isNotEmpty()) selected.clear()
        else if (searching) { searching = false; query = "" }
        else up(current)?.let { current = it } ?: onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val dirAccess = VmFileOps.dirInside(vm, current)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { up(current)?.let { current = it } ?: onDismiss() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.fb_up))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (current == vm) img.name else current.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current.path.removePrefix(vm.path).ifEmpty { "/" }, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.StartEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        if (searching) query = ""
                        searching = !searching
                    }) { Icon(Icons.Rounded.Search, stringResource(R.string.fb_search)) }
                }
                if (searching) {
                    val focus = remember { FocusRequester() }
                    val focusManager = LocalFocusManager.current
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).focusRequester(focus),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.fb_search_hint)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = if (query.isNotEmpty()) ({
                            IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.fb_clear)) }
                        }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    )
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in SortKey.entries) FilterChip(
                        selected = sortKey == k,
                        onClick = { if (sortKey == k) asc = !asc else { sortKey = k; asc = k.defaultAsc } },
                        label = { Text(stringResource(when (k) { SortKey.NAME -> R.string.fb_sort_name; SortKey.DATE -> R.string.fb_sort_date; SortKey.SIZE -> R.string.fb_sort_size })) },
                        leadingIcon = if (sortKey == k) ({ Icon(if (asc) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(18.dp)) }) else null,
                    )
                    FilterChip(selected = hidden, onClick = { hidden = !hidden }, label = { Text(stringResource(R.string.fb_hidden)) })
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                if (!dirAccess) Text(stringResource(R.string.vmf_readonly_folder), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

                val listing by produceState<VmListing>(VmListing.Loading, current, refresh) {
                    val keep = value
                    if (keep !is VmListing.Ok) value = VmListing.Loading
                    value = withContext(Dispatchers.IO) { VmFileOps.list(current)?.let { VmListing.Ok(it) } ?: VmListing.Unreadable }
                }
                val state = rememberLazyListState()
                LaunchedEffect(current) { state.scrollToItem(0) }
                val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

                androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val l = listing) {
                        VmListing.Loading -> Centered { CircularProgressIndicator() }
                        VmListing.Unreadable -> Centered { Message(stringResource(R.string.vmf_unreadable)) }
                        is VmListing.Ok -> {
                            val shown = remember(l, sortKey, asc, hidden, query) {
                                val needle = query.trim()
                                l.nodes.filter { hidden || !it.name.startsWith(".") }
                                    .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                                    .sortedWith(nodeComparator(sortKey, asc))
                            }
                            if (shown.isEmpty()) Centered { Message(stringResource(R.string.fb_empty)) }
                            else LazyColumn(Modifier.fillMaxSize(), state = state) {
                                items(shown, key = { it.path }) { n ->
                                    val checked = selected.any { it.path == n.path }
                                    ListItem(
                                        modifier = Modifier.combinedClickable(
                                            onClick = { openNode(n) },
                                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggle(n) },
                                        ),
                                        leadingContent = { Icon(if (n.isDir) Icons.Rounded.Folder else Icons.Rounded.Description, null) },
                                        headlineContent = { Text(n.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = {
                                            val whenText = date.format(Date(n.modified))
                                            val base = if (n.isDir) whenText else Formatter.formatFileSize(ctx, n.size) + "  ·  " + whenText
                                            Text(if (n.isLink) base + "  ·  " + stringResource(R.string.vmf_link) else base)
                                        },
                                        trailingContent = if (selected.isNotEmpty()) ({ Checkbox(checked = checked, onCheckedChange = null) }) else null,
                                        colors = ListItemDefaults.colors(containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                                    )
                                }
                            }
                        }
                    }
                }

                if (selected.isNotEmpty()) {
                    HorizontalDivider()
                    val states = selected.map { VmFileOps.access(vm, it.file) }
                    val canChange = states.all { it == VmFileOps.Access.NORMAL || it == VmFileOps.Access.CORE }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                Text(stringResource(R.string.fb_selected, selected.size), style = MaterialTheme.typography.titleSmall)
                                Text(Formatter.formatFileSize(ctx, selected.sumOf { it.size }), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { selected.clear() }) { Text(stringResource(R.string.fb_clear)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { copying = selected.toList(); target = null }) {
                                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.vmf_copy))
                            }
                            TextButton(enabled = canChange && selected.size == 1, onClick = { renaming = selected.first() }) {
                                Icon(Icons.Rounded.DriveFileRenameOutline, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.rename))
                            }
                            TextButton(enabled = canChange, onClick = { deleting = selected.toList() }) {
                                val c = if (canChange) MaterialTheme.colorScheme.error else Color.Unspecified
                                Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = c); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.delete), color = c)
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { n ->
        val access = VmFileOps.access(vm, n.file)
        val canChange = access == VmFileOps.Access.NORMAL || access == VmFileOps.Access.CORE
        PreviewDialog(n, canChange, onDismiss = { preview = null },
            onCopy = { preview = null; copying = listOf(n); target = null },
            onRename = { preview = null; renaming = n },
            onDelete = { preview = null; deleting = listOf(n) })
    }

    renaming?.let { n ->
        var name by remember(n.path) { mutableStateOf(n.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.vmf_new_name)) }, modifier = Modifier.fillMaxWidth()) },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(enabled = name.isNotBlank() && name != n.name, onClick = {
                renaming = null; runOp(ctx.getString(R.string.vmf_working_rename)) { VmFileOps.rename(ctx, img.id, n.file, name); null }
            }) { Text(stringResource(R.string.rename)) } },
        )
    }

    deleting?.let { list ->
        val core = list.any { VmFileOps.access(vm, it.file) == VmFileOps.Access.CORE }
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (list.size == 1) stringResource(R.string.vmf_delete_one, list[0].name) else stringResource(R.string.vmf_delete_many, list.size))
                if (core) Text(stringResource(R.string.vmf_delete_core), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                deleting = null
                val files = list.map { it.file }
                runOp(ctx.getString(R.string.vmf_working_delete)) { ctx.getString(R.string.vmf_result_deleted, VmFileOps.delete(ctx, img.id, files) { progress = it }) }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        )
    }

    copying?.let { sources ->
        if (target == null) AlertDialog(
            onDismissRequest = { copying = null }, title = { Text(stringResource(R.string.vmf_copy_to)) },
            text = { Column {
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { target = Target.INSIDE }) { Text(stringResource(R.string.vmf_copy_to_vm)) }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { if (AppPrefs.builtinBrowser(ctx)) target = Target.OUTSIDE else tree.launch(null) }) {
                    Text(stringResource(R.string.vmf_copy_to_device))
                }
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = { copying = null }) { Text(stringResource(R.string.cancel)) } },
        )
        if (target == Target.INSIDE) FolderChooserDialog(
            title = stringResource(R.string.vmf_copy_to_vm), confirm = stringResource(R.string.vmf_copy_here),
            root = vm, start = if (VmFileOps.dirInside(vm, current)) current else vm,
            accept = { VmFileOps.dirInside(vm, it) }, makeDir = { parent, name -> VmFileOps.makeDir(ctx, img.id, parent, name) },
            onDismiss = { target = null }, onChoose = { dest ->
                val files = sources.map { it.file }
                target = null; copying = null
                runOp(ctx.getString(R.string.vmf_working_copy)) { summary(VmFileOps.copyInside(ctx, img.id, files, dest) { progress = it }) }
            }, onSystemPicker = null,
        )
        if (target == Target.OUTSIDE) FolderChooserDialog(
            title = stringResource(R.string.vmf_copy_to_device), confirm = stringResource(R.string.vmf_copy_here),
            root = null, start = null, accept = { true }, makeDir = { parent, name -> VmFileOps.makeOutsideDir(parent, name) },
            onDismiss = { target = null }, onChoose = { dest ->
                val files = sources.map { it.file }
                target = null; copying = null
                runOp(ctx.getString(R.string.vmf_working_copy)) { summary(VmFileOps.copyOutToDir(ctx, files, dest) { progress = it }) }
            }, onSystemPicker = { target = null; tree.launch(null) },
        )
    }

    working?.let { label -> AlertDialog(
        onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(label) }, text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(32.dp)); Text(progress, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = {},
    ) }
    result?.let { text -> AlertDialog(onDismissRequest = { result = null }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.ok)) } }) }
}

@Composable
private fun PreviewDialog(node: VmNode, canChange: Boolean, onDismiss: () -> Unit, onCopy: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val data by produceState<Result<TextPreview>?>(null, node.path) {
        value = withContext(Dispatchers.IO) { runCatching { VmFileOps.readPreview(node.file) } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    Column(Modifier.weight(1f)) {
                        Text(node.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Formatter.formatFileSize(ctx, node.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.vmf_copy)) }
                    if (canChange) {
                        IconButton(onClick = onRename) { Icon(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.rename)) }
                        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                    }
                }
                HorizontalDivider()
                val d = data
                when {
                    d == null -> Centered { CircularProgressIndicator() }
                    d.isFailure -> Centered { Message(stringResource(R.string.vmf_preview_failed)) }
                    else -> {
                        val p = d.getOrThrow()
                        if (p.truncated) Text(stringResource(R.string.vmf_preview_truncated,
                            Formatter.formatFileSize(ctx, VmFileOps.PREVIEW_MAX.toLong()), Formatter.formatFileSize(ctx, p.total)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                items(p.lines.size) { i -> Text(p.lines[i].ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Folder chooser used for the copy destination. */
@Composable
private fun FolderChooserDialog(
    title: String, confirm: String, root: File?, start: File?, accept: (File) -> Boolean,
    makeDir: (File, String) -> Unit, onDismiss: () -> Unit, onChoose: (File) -> Unit, onSystemPicker: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(root != null || FileAccess.granted(ctx)) }
    val life = LocalLifecycleOwner.current
    if (root == null) DisposableEffect(life) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = FileAccess.granted(ctx) }
        life.lifecycle.addObserver(obs); onDispose { life.lifecycle.removeObserver(obs) }
    }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var placesLoaded by remember { mutableStateOf(root != null) }
    var current by remember { mutableStateOf(start) }
    var started by remember { mutableStateOf(root != null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(granted) {
        if (root != null || !granted) return@LaunchedEffect
        places = withContext(Dispatchers.IO) { browserPlaces(ctx) }; placesLoaded = true
        if (!started) { started = true; current = AppPrefs.browserDir(ctx)?.let(::File)?.takeIf { it.isDirectory && it.canRead() } }
    }
    fun up(dir: File): File? {
        if (root != null) return if (dir == root) null else dir.parentFile
        if (places.any { it.dir == dir && it.kind != PlaceKind.DOWNLOADS && it.kind != PlaceKind.DOCUMENTS && it.kind != PlaceKind.FIRMWARE }) return null
        val parent = dir.parentFile ?: return null
        if (parent.path == "/" || parent.path == "/storage" || parent.path == "/storage/emulated") return null
        return if (parent.canRead()) parent else null
    }
    fun back() { val d = current; if (d == null || (root != null && d == root)) onDismiss() else current = up(d) }
    var newFolder by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val dir = current
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.fb_up)) }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (dir != null) Text(dir.path, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.StartEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onSystemPicker != null) TextButton(onClick = onSystemPicker) { Text(stringResource(R.string.fb_system_picker)) }
                }
                HorizontalDivider()
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !granted -> AccessPane(onGranted = { granted = FileAccess.granted(ctx) }, onSystemPicker = { onSystemPicker?.invoke() })
                        dir == null -> PlacesList(places, placesLoaded) { current = it }
                        else -> {
                            val listing by produceState<VmListing>(VmListing.Loading, dir, refresh) {
                                value = withContext(Dispatchers.IO) { VmFileOps.list(dir)?.let { VmListing.Ok(it) } ?: VmListing.Unreadable }
                            }
                            when (val l = listing) {
                                VmListing.Loading -> Centered { CircularProgressIndicator() }
                                VmListing.Unreadable -> Centered { Message(stringResource(R.string.vmf_unreadable)) }
                                is VmListing.Ok -> {
                                    val folders = remember(l) { l.nodes.filter { it.isDir && accept(it.file) }.sortedWith(nodeComparator(SortKey.NAME, true)) }
                                    if (folders.isEmpty()) Centered { Message(stringResource(R.string.vmf_no_folders)) }
                                    else LazyColumn(Modifier.fillMaxSize()) { items(folders, key = { it.path }) { n ->
                                        ListItem(modifier = Modifier.clickable { current = n.file }, leadingContent = { Icon(Icons.Rounded.Folder, null) },
                                            headlineContent = { Text(n.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                                    } }
                                }
                            }
                        }
                    }
                }
                if (granted && dir != null) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { newName = ""; newFolder = true }) {
                            Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.vmf_new_folder))
                        }
                        Spacer(Modifier.weight(1f)); Button(enabled = accept(dir), onClick = { onChoose(dir) }) { Text(confirm) }
                    }
                }
            }
        }
    }
    if (newFolder) AlertDialog(
        onDismissRequest = { newFolder = false }, title = { Text(stringResource(R.string.vmf_new_folder)) },
        text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
            label = { Text(stringResource(R.string.vmf_folder_name)) }, modifier = Modifier.fillMaxWidth()) },
        dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = {
            newFolder = false; val parent = current ?: return@TextButton; val n = newName
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { makeDir(parent, n) } }
                r.onSuccess { refresh++ }.onFailure { error = ctx.getString(R.string.vmf_failed, it.userMessage(ctx)) }
            }
        }) { Text(stringResource(R.string.vmf_create)) } },
    )
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } }) }
}
