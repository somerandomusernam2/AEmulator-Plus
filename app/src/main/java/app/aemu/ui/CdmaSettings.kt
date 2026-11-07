/* AEmulator Super Plus, 2026-10-23: CDMA modem option. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.VmSettings

/**
 * CDMA modem switch of the radio section. It shows what will happen: on by default when the firmware declares
 * telephony.lteOnCdmaDevice=1 ([firmwareLteOnCdma]), off otherwise. Choosing the firmware's own default goes back
 * to "automatic" (null), so a later firmware update or re-import keeps following it.
 */
@Composable
internal fun CdmaToggle(s: VmSettings, firmwareLteOnCdma: Boolean, set: (VmSettings) -> Unit) {
    val on = s.cdma ?: firmwareLteOnCdma
    val note = stringResource(if (firmwareLteOnCdma) R.string.vs_cdma_fw_yes else R.string.vs_cdma_fw_no)
    Toggle(stringResource(R.string.vs_cdma), stringResource(R.string.vs_cdma_sub) + " " + note, on) { v ->
        set(s.copy(cdma = if (v == firmwareLteOnCdma) null else v))
    }
}
