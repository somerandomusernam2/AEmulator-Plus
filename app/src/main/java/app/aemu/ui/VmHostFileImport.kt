package app.aemu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.GuestImage
import app.aemu.core.VmHostFileImport
import app.aemu.core.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks host files and copies them into /data/local/tmp in the selected VM. */
@Composable
internal fun VmHostFileImportSection(img: GuestImage, busy: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember(img.id) { mutableStateOf<String?>(null) }
    var importing by remember(img.id) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            importing = true
            message = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { VmHostFileImport.install(ctx, img.id, uris) { name -> message = name } }
                }
                importing = false
                message = result.fold(
                    { count -> ctx.getString(R.string.vmf_import_done, count) },
                    { error -> ctx.getString(R.string.vmf_failed, error.userMessage(ctx)) },
                )
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        TextButton(enabled = !busy && !importing, modifier = Modifier.fillMaxWidth(),
            onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(stringResource(if (importing) R.string.vmf_importing else R.string.vmf_import_host))
        }
        Text(stringResource(R.string.vmf_import_destination), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}
