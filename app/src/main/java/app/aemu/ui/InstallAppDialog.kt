/* AEmulator Plus addition, 2026-11-27: install the APK of an app already installed on the phone into a VM.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import app.aemu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** An app installed on the phone, as shown in [InstalledAppsDialog]. */
internal class HostApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val system: Boolean,
)

/**
 * The APK files of [packageName]: the base APK and, when [withSplits], the config splits of an app bundle. Unreadable
 * entries are left out; an empty list means the APK cannot be taken from this phone (some system apps, other users).
 */
internal fun hostAppApkFiles(ctx: Context, packageName: String, withSplits: Boolean): List<File> {
    val info = runCatching { ctx.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return emptyList()
    return buildList {
        info.sourceDir?.let { File(it) }?.takeIf { it.isFile }?.let { add(it) }
        if (withSplits) info.splitSourceDirs?.filterNotNull()?.forEach { path ->
            val file = File(path)
            if (file.isFile) add(file)
        }
    }
}

private fun loadHostApps(ctx: Context): List<HostApp> {
    val pm = ctx.packageManager
    // every app the user installed, plus the preinstalled apps that have a launcher; a plain getInstalledApplications()
    // would also list hundreds of system services nobody can install into an old firmware
    val found = LinkedHashMap<String, ApplicationInfo>()
    runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList()).forEach { info ->
        if (info.packageName != ctx.packageName && (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0) found[info.packageName] = info
    }
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList()).forEach { resolved ->
        resolved.activityInfo?.applicationInfo?.let { info ->
            if (info.packageName != ctx.packageName) found.putIfAbsent(info.packageName, info)
        }
    }
    return found.values.map { info ->
        val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName
        val icon = runCatching { info.loadIcon(pm).toBitmap(128, 128) }.getOrNull()
        HostApp(info.packageName, label, icon, (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
    }.sortedWith(compareBy<HostApp> { it.system }.thenBy { it.label.lowercase() })
}

/**
 * The phone's own apps: tap one to install its APK into the running VM. The emulator itself is left out. User-installed
 * apps come first; system apps stay in the list because preinstalled apps are sometimes wanted too.
 */
@Composable
internal fun InstalledAppsDialog(onDismiss: () -> Unit, onInstall: (HostApp) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<HostApp>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { runCatching { loadHostApps(ctx) }.getOrDefault(emptyList()) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.m_install_app_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.m_install_app_sub), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true, placeholder = { Text(stringResource(R.string.m_install_app_search)) })
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val list = apps
                    if (list == null) {
                        Centered { CircularProgressIndicator() }
                    } else {
                        val needle = query.trim()
                        val shown = list.filter {
                            needle.isEmpty() || it.label.contains(needle, ignoreCase = true) || it.packageName.contains(needle, ignoreCase = true)
                        }
                        if (shown.isEmpty()) {
                            Centered { Text(stringResource(R.string.m_install_app_empty)) }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(shown, key = { it.packageName }) { hostApp ->
                                    ListItem(
                                        modifier = Modifier.clickable { onInstall(hostApp) },
                                        leadingContent = {
                                            val icon = hostApp.icon
                                            if (icon != null) {
                                                Image(icon.asImageBitmap(), contentDescription = null,
                                                    modifier = Modifier.size(40.dp).clip(CircleShape))
                                            } else {
                                                Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(40.dp))
                                            }
                                        },
                                        headlineContent = { Text(hostApp.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(hostApp.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
