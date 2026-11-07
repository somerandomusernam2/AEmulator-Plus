/* Added for AEmulator Plus, 2026-11-01: folders in the library. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.GuestImage

/** A folder in the library: tap to open or close it, press and hold to move it, ⋮ for rename and delete. */
@Composable
internal fun FolderHeader(
    folder: LibFolder,
    hovered: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (folder.open) 180f else 0f, label = "folderArrow")
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onToggle,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = if (hovered) colors.primaryContainer else colors.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (folder.open) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null,
                tint = if (hovered) colors.onPrimaryContainer else colors.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = colors.onSecondaryContainer)
                Text(
                    if (folder.roms.isEmpty()) stringResource(R.string.folder_empty)
                    else ctx.resources.getQuantityString(R.plurals.folder_roms, folder.roms.size, folder.roms.size),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.ExpandMore,
                stringResource(if (folder.open) R.string.folder_close else R.string.folder_open),
                tint = colors.onSecondaryContainer, modifier = Modifier.rotate(arrow),
            )
            Column {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.menu), tint = colors.onSecondaryContainer) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.folder_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.folder_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Asks for a folder name (new folder or rename). An empty name falls back to the default one. */
@Composable
internal fun FolderNameDialog(title: Int, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val fallback = stringResource(R.string.folder_default_name)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { Button(onClick = { onConfirm(name.trim().ifEmpty { fallback }) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Pick the folder a ROM goes to: out of any folder, one of the folders, or a new one. */
@Composable
internal fun MoveToFolderDialog(
    img: GuestImage,
    layout: LibLayout,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onNew: () -> Unit,
) {
    val current = layout.folderOf(img.id)
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOpen, null) },
        title = { Text(img.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.folder_none)) },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    trailingContent = { if (current == null) Icon(Icons.Rounded.Check, null) },
                    colors = transparent,
                    modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onPick(null) },
                )
                for (id in layout.top) {
                    val f = layout.folders[id] ?: continue
                    ListItem(
                        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Rounded.Folder, null) },
                        trailingContent = { if (current == id) Icon(Icons.Rounded.Check, null) },
                        colors = transparent,
                        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onPick(id) },
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.folder_new_dots)) },
                    leadingContent = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    colors = transparent,
                    modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onNew),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
