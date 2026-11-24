/* Modified for AEmulator Sunset, 2026-10-03: VM export, motion and host resolution.
 * Modified for AEmulator Super Plus, 2026-10-11: fake SIM card section, Wi-Fi signal and neighbours.
 * Modified for AEmulator Plus, 2026-10-28: Android 14+ watchdog toggle.
 * Modified for AEmulator Plus, 2026-11-02: Wi-Fi is offered for Android 8 - 17 (engine of the running VM, not the stored one).
 * GPL-3.0; upstream attribution retained in NOTICE.md. */
package app.aemu.ui

import app.aemu.core.userMessage
import app.aemu.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aemu.core.CdmaMode
import app.aemu.core.CpuInfo
import app.aemu.core.Engine
import app.aemu.core.GuestImage
import app.aemu.core.GyroLock
import app.aemu.core.MemoryMode
import app.aemu.core.NetworkType
import app.aemu.core.RamChoice
import app.aemu.core.RamType
import app.aemu.core.StorageSpoof
import app.aemu.core.StorageType
import app.aemu.core.SocProfiles
import app.aemu.core.SocSpec
import app.aemu.core.SocIsa
import app.aemu.core.X86Cpu
import kotlin.math.roundToInt
import app.aemu.core.VmSettings
import app.aemu.core.WifiEmu
import app.aemu.core.VmStorageLease
import app.aemu.core.VmDataReset
import app.aemu.core.ImageStore
import app.aemu.core.RefreshRate
import app.aemu.core.ResolutionPreset
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RESOLUTIONS = ResolutionPreset.entries

// Modified for AEmulator Plus (2026-11-04): automatic, the presets, host / max, then "custom" (a box for an MB amount).
// RAM_CUSTOM only marks the last slider position; it is never stored.
private const val RAM_CUSTOM = -2
private val RAM_STEPS = listOf(RamChoice.AUTO) + RamChoice.PRESETS_MB + RamChoice.HOST + RAM_CUSTOM

// Added for AEmulator Plus (2026-11-20): the refresh rate slider is the same shape: the presets, host, then "custom" (a box for a Hz amount).
private const val HZ_CUSTOM = -2
private val HZ_STEPS = RefreshRate.PRESETS_HZ + RefreshRate.HOST + HZ_CUSTOM

private fun ramText(mb: Int): String =
    if (mb < 1024) "$mb MB"
    else if (mb % 1024 == 0) "${mb / 1024} GB"
    else String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0) + " (" + mb + " MB)"

@Composable
fun SettingsSheet(img: GuestImage, onDismiss: () -> Unit, onSave: (VmSettings, String) -> Unit) {
    var s by remember { mutableStateOf(img.settings) }
    var name by remember { mutableStateOf(img.name) }
    val ctx = LocalContext.current
    // follows the settings being edited: with 32-bit translation off, a 32-bit 8.0+ firmware runs on the universal engine
    val wifiOffered = WifiEmu.offered(img.copy(settings = s))
    // CDMA modem: automatic from the firmware's telephony.lteOnCdmaDevice unless the user chose
    val fwLteOnCdma = remember(img.id) { CdmaMode.supported(img.api) && CdmaMode.firmwareLteOnCdma(app.aemu.core.VmPaths(ctx, img.id)) }
    val cdmaOn = CdmaMode.supported(img.api) && (s.cdma ?: fwLteOnCdma)
    val scope = rememberCoroutineScope()
    val storagePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    var resetConfirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { if (!resetting) onDismiss() }, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            Text(img.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            // rename the machine; a blank name keeps the current one (see LibraryModel.updateSettings)
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.vs_screen), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (s.legacyEngine && img.api < 14) {
                Text(stringResource(R.string.vs_fixed_2x), style = MaterialTheme.typography.bodyMedium)
            } else {
                val firmwareDensity = remember(img.id) { ResolutionPreset.buildPropDensity(app.aemu.core.VmPaths(ctx, img.id).root) }
                val hostScreen = remember(ctx) {
                    val point = android.graphics.Point()
                    val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                    wm.defaultDisplay.getRealSize(point)
                    val (hostWidth, hostHeight) = app.aemu.core.DisplayGeometry.natural(point.x, point.y, wm.defaultDisplay.rotation)
                    Triple(hostWidth, hostHeight, ctx.resources.displayMetrics.densityDpi)
                }
                fun followingHost(value: VmSettings) = value.copy(width = hostScreen.first and -2,
                    height = hostScreen.second and -2, density = hostScreen.third)
                LaunchedEffect(img.id, s.hostResolution) { if (s.hostResolution) s = followingHost(s) }
                var presetMenu by remember { mutableStateOf(false) }
                val current = RESOLUTIONS.firstOrNull { it.selected(s) }
                val hostLabel = stringResource(R.string.vs_resolution_host)
                val customLabel = stringResource(R.string.vs_resolution_custom)
                fun name(r: ResolutionPreset) = if (r.isHost) hostLabel else r.label
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { presetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(current?.let(::name) ?: customLabel, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("▾")
                    }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        RESOLUTIONS.forEach { r ->
                            DropdownMenuItem(
                                text = { Text(name(r), fontWeight = if (r == current) FontWeight.Bold else null) },
                                onClick = {
                                    s = if (r.isHost) followingHost(s.copy(hostResolution = true)) else r.apply(s, firmwareDensity)
                                    presetMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumField(stringResource(R.string.vs_width), s.width, Modifier.weight(1f)) { s = s.copy(width = it.coerceAtLeast(2) and 0x7ffffffe, hostResolution = false) }
                    NumField(stringResource(R.string.vs_height), s.height, Modifier.weight(1f)) { s = s.copy(height = it.coerceAtLeast(2) and 0x7ffffffe, hostResolution = false) }
                    NumField(stringResource(R.string.vs_dpi), s.density, Modifier.weight(1f)) { s = s.copy(density = it.coerceIn(96, 640), hostResolution = false) }
                }
                Text(stringResource(R.string.vs_screen_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                if (s.hostResolution) Text(stringResource(R.string.vs_host_resolution_sub),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                RefreshRatePicker(s) { s = it }
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.vs_perf), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_gpu), stringResource(R.string.vs_gpu_sub), s.gpu) { s = s.copy(gpu = it) }
            if (s.gpu) Toggle(stringResource(R.string.vs_gl_trace), stringResource(R.string.vs_gl_trace_sub), s.glTrace) { s = s.copy(glTrace = it) }
            if (img.api >= 14) Toggle(stringResource(R.string.vs_hwui), stringResource(R.string.vs_hwui_sub), s.hwui) { s = s.copy(hwui = it) }
            Toggle(stringResource(R.string.vs_jit), stringResource(R.string.vs_jit_sub), s.jit) { s = s.copy(jit = it) }
            if (img.api >= 21) Toggle(stringResource(R.string.vs_fulldex), stringResource(R.string.vs_fulldex_sub), s.fullDexopt) { s = s.copy(fullDexopt = it) }
            if (img.api >= 21) Toggle(stringResource(R.string.vs_no_dex2oat), stringResource(R.string.vs_no_dex2oat_sub), s.disableDex2oat) { s = s.copy(disableDex2oat = it) }
            if (img.api < 14) Toggle(stringResource(R.string.vs_legacy), stringResource(R.string.vs_legacy_sub), s.legacyEngine) { s = s.copy(legacyEngine = it) }
            if (img.api >= 26 && img.abi.startsWith("armeabi")) Toggle(
                "Translate on the native engine",
                "32-bit firmware of Android 8+ (Wear OS 9, ...): run it on the arm64 engine through qemu-arm. The universal engine cannot start it (binder protocol 8). With the GPU option on, apps' OpenGL ES drawing goes to the phone's GPU (8.0+). Takes effect on the next start.",
                s.translate32
            ) { s = s.copy(translate32 = it) }
            val has32 = CpuInfo.hasAArch32
            Toggle(
                stringResource(R.string.vs_native32),
                if (has32) stringResource(R.string.vs_native32_sub) else stringResource(R.string.vs_native32_unsupported),
                s.native32 && has32
            ) { if (has32) s = s.copy(native32 = it) }
            Toggle(stringResource(R.string.vs_thermal), stringResource(R.string.vs_thermal_sub), s.thermalPassthrough) { s = s.copy(thermalPassthrough = it) }
            // the custom box stays open while it is edited, even when the typed amount equals one of the presets
            var ramCustom by remember { mutableStateOf(s.ramMb > 0 && s.ramMb !in RamChoice.PRESETS_MB) }
            var ramBox by remember { mutableStateOf(if (s.ramMb > 0 && s.ramMb !in RamChoice.PRESETS_MB) s.ramMb.toString() else "") }
            val hostMb = remember { RamChoice.hostMb() }
            val ramIdx = if (ramCustom) RAM_STEPS.lastIndex else RAM_STEPS.indexOf(s.ramMb).coerceAtLeast(0)
            ListItem(
                headlineContent = { Text(stringResource(R.string.vs_ram)) },
                supportingContent = {
                    Column {
                        Text(when {
                            ramCustom -> stringResource(R.string.vs_ram_custom)
                            s.ramMb == RamChoice.AUTO -> stringResource(R.string.vs_ram_auto)
                            s.ramMb == RamChoice.HOST -> stringResource(R.string.vs_ram_host) + " (" + ramText(hostMb) + ")"
                            else -> ramText(s.ramMb)
                        })
                        Slider(value = ramIdx.toFloat(), onValueChange = {
                            val step = RAM_STEPS[it.roundToInt().coerceIn(0, RAM_STEPS.lastIndex)]
                            if (step == RAM_CUSTOM) {
                                if (!ramCustom) {
                                    // start from what is selected now
                                    val seed = when { s.ramMb > 0 -> s.ramMb; s.ramMb == RamChoice.HOST -> hostMb; else -> 2048 }
                                    ramCustom = true; ramBox = seed.toString(); s = s.copy(ramMb = seed)
                                }
                            } else { ramCustom = false; s = s.copy(ramMb = step) }
                        }, valueRange = 0f..RAM_STEPS.lastIndex.toFloat(), steps = RAM_STEPS.size - 2)
                        if (ramCustom) OutlinedTextField(
                            value = ramBox,
                            onValueChange = { v ->
                                val digits = v.filter { c -> c.isDigit() }.take(6)
                                ramBox = digits
                                // an amount that is too small is shown as an error and the last valid one is kept
                                digits.toIntOrNull()?.takeIf { n -> n >= RamChoice.CUSTOM_MIN_MB }?.let { n -> s = s.copy(ramMb = n) }
                            },
                            label = { Text(stringResource(R.string.vs_ram_custom_hint)) },
                            suffix = { Text("MB") },
                            singleLine = true,
                            isError = (ramBox.toIntOrNull() ?: 0) < RamChoice.CUSTOM_MIN_MB,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            StoragePicker(s) { s = it }
            Toggle(stringResource(R.string.vs_lowram), stringResource(R.string.vs_lowram_sub), s.lowRam) { s = s.copy(lowRam = it) }
            MemoryModePicker(MemoryMode.parse(s.memoryMode)) { s = s.copy(memoryMode = it.id) }
            Toggle(stringResource(R.string.vs_proxy), stringResource(R.string.vs_proxy_sub), s.netProxy) { s = s.copy(netProxy = it) }
            Toggle(stringResource(R.string.vs_netdebug), stringResource(R.string.vs_netdebug_sub), s.netDebug) { s = s.copy(netDebug = it) }
            Toggle(stringResource(R.string.vs_vibration), stringResource(R.string.vs_vibration_sub), s.vibration) { s = s.copy(vibration = it) }
            Toggle(stringResource(R.string.vs_host_battery), stringResource(R.string.vs_host_battery_sub), s.hostBattery) { s = s.copy(hostBattery = it) }
            Toggle(stringResource(R.string.vs_folder_share), stringResource(R.string.vs_folder_share_sub), s.folderShare) { on ->
                if (on && !FileAccess.granted(ctx)) {
                    if (FileAccess.viaSettings) FileAccess.openSettings(ctx)
                    else storagePermLauncher.launch(FileAccess.runtimePermissions())
                }
                s = s.copy(folderShare = on)
            }
            if (img.api in 9..25) Toggle(stringResource(R.string.vs_no_sensors), stringResource(R.string.vs_no_sensors_sub), img.sensorsOff(s)) { s = s.copy(disableSensors = it) }
            if (img.api in 9..25 && !img.sensorsOff(s)) Toggle(stringResource(R.string.vs_motion), stringResource(R.string.vs_motion_sub), s.motionSensors) { s = s.copy(motionSensors = it) }
            if (img.api in 9..25 && !img.sensorsOff(s) && s.motionSensors) GyroLockPicker(s.gyroLock) { s = s.copy(gyroLock = it) }
            if (img.api >= 16) Toggle(stringResource(R.string.vs_skip_setup), stringResource(R.string.vs_skip_setup_sub), s.skipSetupWizard) { s = s.copy(skipSetupWizard = it) }
            if (img.api in 9..25) Toggle(stringResource(R.string.vs_disable_google), stringResource(R.string.vs_disable_google_sub), s.disableGoogleApps) { s = s.copy(disableGoogleApps = it) }
            if (img.api in 9..25) {
                Toggle(stringResource(R.string.vs_bluetooth), stringResource(R.string.vs_bluetooth_sub), s.bluetooth) { s = s.copy(bluetooth = it) }
            }
            if (img.api >= 26) Toggle(stringResource(R.string.vs_sf_tombstone), stringResource(R.string.vs_sf_tombstone_sub), s.sfTombstone) { s = s.copy(sfTombstone = it) }
            if (img.api >= 34) Toggle(stringResource(R.string.vs_art_boot_image), stringResource(R.string.vs_art_boot_image_sub), s.useArtBootImage) { s = s.copy(useArtBootImage = it) }
            if (img.api >= 34) Toggle(stringResource(R.string.vs_no_watchdog), stringResource(R.string.vs_no_watchdog_sub), s.disableWatchdog) { s = s.copy(disableWatchdog = it) }
            if (img.api in 29..33) Toggle(stringResource(R.string.vs_skip_perm_wait), stringResource(R.string.vs_skip_perm_wait_sub), s.skipPermissionWait) { s = s.copy(skipPermissionWait = it) }
            if (wifiOffered) {
                Toggle(stringResource(R.string.vs_wifi), stringResource(R.string.vs_wifi_sub), s.wifi) { s = s.copy(wifi = it) }
                if (s.wifi) WifiFields(s) { s = it }
            }
            if (img.api in 14..25) {
                Toggle(stringResource(R.string.vs_camera), stringResource(R.string.vs_camera_sub), s.camera) { s = s.copy(camera = it) }
            }
            if (img.api in 14..25) {
                Toggle(stringResource(R.string.vs_microphone), stringResource(R.string.vs_microphone_sub), s.microphone) { s = s.copy(microphone = it) }
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_radio_title), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_radio), stringResource(R.string.vs_radio_sub), s.radio) { s = s.copy(radio = it) }
            if (s.radio) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = s.imei, onValueChange = { v -> s = s.copy(imei = v.filter(Char::isDigit).take(15)) },
                    label = { Text("IMEI") }, placeholder = { Text(VmSettings.DEFAULT_IMEI) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                TextButton(onClick = { s = s.copy(imei = VmSettings.randomImei()) }) { Text(stringResource(R.string.vs_random)) }
            }
            if (s.radio && CdmaMode.supported(img.api)) CdmaToggle(s, fwLteOnCdma) { s = it }
            if (s.radio) NetworkTypePicker(s.networkType, img.api, cdmaOn, cdmaOn && fwLteOnCdma) { s = s.copy(networkType = it) }
            if (s.radio) SimSection(img, s) { s = it }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_device_id), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedTextField(value = s.serial, onValueChange = { v -> s = s.copy(serial = VmSettings.cleanSerial(v)) },
                    label = { Text(stringResource(R.string.vs_serial)) }, placeholder = { Text(VmSettings.DEFAULT_SERIAL) },
                    supportingText = { Text(stringResource(R.string.vs_serial_sub)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
                TextButton(onClick = { s = s.copy(serial = VmSettings.randomSerial()) }) { Text(stringResource(R.string.vs_random)) }
            }
            OutlinedTextField(value = s.kernel, onValueChange = { v -> s = s.copy(kernel = VmSettings.cleanKernel(v)) },
                label = { Text(stringResource(R.string.vs_kernel)) }, placeholder = { Text(VmSettings.DEFAULT_KERNEL) },
                supportingText = { Text(stringResource(R.string.vs_kernel_sub)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text(stringResource(R.string.vs_kernel_build), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.vs_kernel_build_sub), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedTextField(value = s.kernelBranch, onValueChange = { v -> s = s.copy(kernelBranch = VmSettings.cleanToken(v, VmSettings.TOKEN_MAX)) },
                    label = { Text(stringResource(R.string.vs_kernel_branch), maxLines = 1) }, placeholder = { Text("master") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                OutlinedTextField(value = s.kernelCommit, onValueChange = { v -> s = s.copy(kernelCommit = VmSettings.cleanToken(v, 40)) },
                    label = { Text(stringResource(R.string.vs_kernel_commit), maxLines = 1) }, placeholder = { Text("1a2b3c4d") }, singleLine = true,
                    modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedTextField(value = s.kernelUser, onValueChange = { v -> s = s.copy(kernelUser = VmSettings.cleanToken(v, VmSettings.TOKEN_MAX)) },
                    label = { Text(stringResource(R.string.vs_kernel_user), maxLines = 1) }, placeholder = { Text("aemu") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                OutlinedTextField(value = s.kernelHost, onValueChange = { v -> s = s.copy(kernelHost = VmSettings.cleanToken(v, 64)) },
                    label = { Text(stringResource(R.string.vs_kernel_host), maxLines = 1) }, placeholder = { Text("aemu") }, singleLine = true,
                    modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedTextField(value = s.kernelBuild, onValueChange = { v -> s = s.copy(kernelBuild = v.filter { it in '0'..'9' }.take(6)) },
                    label = { Text(stringResource(R.string.vs_kernel_number), maxLines = 1) }, placeholder = { Text("1") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                OutlinedTextField(value = s.kernelFlags, onValueChange = { v -> s = s.copy(kernelFlags = VmSettings.cleanText(v, 40)) },
                    label = { Text(stringResource(R.string.vs_kernel_flags), maxLines = 1) }, placeholder = { Text("SMP PREEMPT") }, singleLine = true,
                    modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedTextField(value = s.kernelCompiler, onValueChange = { v -> s = s.copy(kernelCompiler = VmSettings.cleanCompiler(v)) },
                    label = { Text(stringResource(R.string.vs_kernel_compiler), maxLines = 1) }, placeholder = { Text("gcc version 4.6") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                OutlinedTextField(value = s.kernelDate, onValueChange = { v -> s = s.copy(kernelDate = VmSettings.cleanText(v, 40)) },
                    label = { Text(stringResource(R.string.vs_kernel_date), maxLines = 1) }, placeholder = { Text("Thu Jan 1 00:00:00 UTC 2026") }, singleLine = true,
                    modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.vs_kernel_preview) + " " + VmSettings.procVersion(s).trim(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(value = s.baseband, onValueChange = { v -> s = s.copy(baseband = VmSettings.cleanBaseband(v)) },
                label = { Text(stringResource(R.string.vs_baseband)) }, placeholder = { Text(VmSettings.DEFAULT_BASEBAND) },
                supportingText = { Text(stringResource(R.string.vs_baseband_sub)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            // SoC and GPU the VM reports live with the rest of the device identity
            SocPicker(img, s) { s = it }
            RamTypePicker(s) { s = it }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_advanced), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = s.qemuArgs, onValueChange = { s = s.copy(qemuArgs = it) },
                label = { Text(stringResource(R.string.vs_qemu_args)) }, supportingText = { Text(stringResource(R.string.vs_qemu_args_sub)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            OutlinedTextField(value = s.straceServices, onValueChange = { s = s.copy(straceServices = it) },
                label = { Text(stringResource(R.string.vs_strace)) }, placeholder = { Text("mediaserver,zygote") },
                supportingText = { Text(stringResource(R.string.vs_strace_sub)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            HardwarePicker(s.hardware) { s = s.copy(hardware = it) }

            Spacer(Modifier.height(8.dp))
            CscImportSection(img, resetting) { resetting = it }

            MagiskSection(img, s.magisk, resetting, { resetting = it }) { s = s.copy(magisk = it) }

            Spacer(Modifier.height(8.dp))
            OtaSection(img, resetting) { resetting = it }

            Spacer(Modifier.height(8.dp))
            PartitionImportSection(img, resetting) { resetting = it }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_controls), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_nav), stringResource(R.string.vs_nav_sub), s.showNavBar) { s = s.copy(showNavBar = it) }
            Toggle(stringResource(R.string.vs_guest_nav), stringResource(R.string.vs_guest_nav_sub), s.guestNavBar) { s = s.copy(guestNavBar = it) }
            Toggle(stringResource(R.string.vs_hide_menu), stringResource(R.string.vs_hide_menu_sub), s.hideMenuButton) { s = s.copy(hideMenuButton = it) }
            NavigationSettings(s, app.aemu.core.LegacyFirmware.isPreRelease(img.release)) { s = it }
            Toggle(stringResource(R.string.vs_awake), stringResource(R.string.vs_awake_sub), s.keepScreenOn) { s = s.copy(keepScreenOn = it) }
            Toggle(stringResource(R.string.as_led), stringResource(R.string.as_led_sub), s.showLed) { s = s.copy(showLed = it) }
            Toggle(stringResource(R.string.vs_single), stringResource(R.string.vs_single_sub), s.mtMode == 4) { s = s.copy(mtMode = if (it) 4 else 0) }

            Spacer(Modifier.height(16.dp))
            VmExportSection(img, s, resetting) { resetting = it }
            VmImportSection(img, resetting, { resetting = it }) { s = it }
            VmFilesSection(img, resetting)
            TextButton(enabled = !resetting, onClick = { resetConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vm_reset_data), color = MaterialTheme.colorScheme.error)
            }
            resetMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            // fields that are not shown cannot hold Save back
            val wifiOk = !(s.wifi && wifiOffered) || (WifiEmu.cleanSsid(s.wifiSsid).isNotEmpty() && WifiEmu.passwordValid(s.wifiPassword))
            Button(enabled = !resetting && wifiOk, onClick = { onSave(s, name) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (resetConfirm) AlertDialog(
        onDismissRequest = { resetConfirm = false },
        title = { Text(stringResource(R.string.vm_reset_data)) },
        text = { Text(stringResource(R.string.vm_reset_warning)) },
        dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            resetConfirm = false
            resetting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        VmStorageLease(ctx.filesDir).use { lease ->
                            lease.acquire()
                            app.aemu.core.GuestStorageWriters.requireIdle(ctx)
                            VmDataReset.reset(ImageStore.imagesDir(ctx), img.id) { file ->
                                val stat = android.system.Os.lstat(file.path)
                                String.format(java.util.Locale.ROOT, "%016x %016x", stat.st_dev, stat.st_ino)
                            }
                        }
                    }
                }
                resetting = false
                resetMessage = if (result.isSuccess) ctx.getString(R.string.vm_reset_done)
                    else ctx.getString(R.string.vm_reset_failed)
            }
        }) { Text(stringResource(R.string.vm_reset_data)) } },
    )
}

@Composable
internal fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = { Switch(checked = on, onCheckedChange = set) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Name and optional password of the emulated Wi-Fi network (blank password = open network). */
@Composable
private fun WifiFields(s: VmSettings, set: (VmSettings) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value = s.wifiSsid, onValueChange = { set(s.copy(wifiSsid = WifiEmu.cleanSsid(it))) },
        label = { Text(stringResource(R.string.vs_wifi_ssid)) }, singleLine = true,
        isError = s.wifiSsid.isEmpty(),
        supportingText = { Text(stringResource(if (s.wifiSsid.isEmpty()) R.string.vs_wifi_ssid_error else R.string.vs_wifi_ssid_sub)) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    OutlinedTextField(value = s.wifiPassword, onValueChange = { set(s.copy(wifiPassword = WifiEmu.cleanPassword(it))) },
        label = { Text(stringResource(R.string.vs_wifi_password)) }, singleLine = true,
        isError = !WifiEmu.passwordValid(s.wifiPassword),
        supportingText = { Text(stringResource(if (WifiEmu.passwordValid(s.wifiPassword)) R.string.vs_wifi_password_sub else R.string.vs_wifi_password_error)) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.vs_wifi_hide else R.string.vs_wifi_show)) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    var open by remember { mutableStateOf(false) }
    fun bars(n: Int) = "▂▄▆█".take(n) + "·".repeat(WifiEmu.MAX_BARS - n)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_wifi_signal)) },
        supportingContent = { Text(stringResource(R.string.vs_wifi_signal_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(bars(s.wifiSignal)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (n in WifiEmu.MAX_BARS downTo 1) DropdownMenuItem(text = { Text(bars(n)) }, onClick = { open = false; set(s.copy(wifiSignal = n)) })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
    Toggle(stringResource(R.string.vs_wifi_neighbors), stringResource(R.string.vs_wifi_neighbors_sub), s.wifiNeighbors) { set(s.copy(wifiNeighbors = it)) }
}

/**
 * Added for AEmulator Plus (2026-11-20). The refresh rate the guest's screen reports and draws at: a slider over the presets, the
 * phone's own maximum and a custom amount. Above what the phone's display can show the guest still reports the chosen rate and
 * renders at the phone's maximum (see [RefreshRate]); a short warning says so. Without the GPU bridge (SwiftShader) it is fixed at 60.
 */
@Composable
private fun RefreshRatePicker(s: VmSettings, set: (VmSettings) -> Unit) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val hostHz = remember { RefreshRate.hostHz(appCtx) }
    val stored = if (s.fbHz == RefreshRate.HOST || s.fbHz in RefreshRate.CUSTOM_MIN_HZ..RefreshRate.CUSTOM_MAX_HZ) s.fbHz else RefreshRate.DEFAULT_HZ
    // the custom box stays open while it is edited, even when the typed amount equals one of the presets
    var custom by remember { mutableStateOf(stored != RefreshRate.HOST && stored !in RefreshRate.PRESETS_HZ) }
    var box by remember { mutableStateOf(if (custom) stored.toString() else "") }
    val idx = if (custom) HZ_STEPS.lastIndex else HZ_STEPS.indexOf(stored).coerceAtLeast(0)
    val usable = s.gpu
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_refresh)) },
        supportingContent = {
            Column {
                Text(when {
                    !usable -> "60 Hz"
                    custom -> stringResource(R.string.vs_refresh_custom)
                    stored == RefreshRate.HOST -> stringResource(R.string.vs_refresh_host) + " (" + hostHz + " Hz)"
                    else -> "$stored Hz"
                })
                Slider(
                    value = if (usable) idx.toFloat() else HZ_STEPS.indexOf(RefreshRate.DEFAULT_HZ).toFloat(),
                    onValueChange = {
                        val step = HZ_STEPS[it.roundToInt().coerceIn(0, HZ_STEPS.lastIndex)]
                        if (step == HZ_CUSTOM) {
                            if (!custom) {
                                // start from what is selected now
                                val seed = if (stored == RefreshRate.HOST) hostHz else stored
                                custom = true; box = seed.toString(); set(s.copy(fbHz = seed))
                            }
                        } else { custom = false; set(s.copy(fbHz = step)) }
                    },
                    enabled = usable,
                    valueRange = 0f..HZ_STEPS.lastIndex.toFloat(), steps = HZ_STEPS.size - 2)
                if (usable && custom) OutlinedTextField(
                    value = box,
                    onValueChange = { v ->
                        val digits = v.filter { c -> c.isDigit() }.take(4)
                        box = digits
                        // an amount outside the range is shown as an error and the last valid one is kept
                        digits.toIntOrNull()?.takeIf { n -> n in RefreshRate.CUSTOM_MIN_HZ..RefreshRate.CUSTOM_MAX_HZ }?.let { n -> set(s.copy(fbHz = n)) }
                    },
                    label = { Text(stringResource(R.string.vs_refresh_custom_hint)) },
                    suffix = { Text("Hz") },
                    singleLine = true,
                    isError = (box.toIntOrNull() ?: 0) !in RefreshRate.CUSTOM_MIN_HZ..RefreshRate.CUSTOM_MAX_HZ,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                val warn = when {
                    !usable -> stringResource(R.string.vs_refresh_needs_gpu)
                    RefreshRate.resolve(s.fbHz, hostHz, true).capped -> stringResource(R.string.vs_refresh_cap, hostHz, s.fbHz)
                    else -> null
                }
                Text(warn ?: stringResource(R.string.vs_refresh_sub), style = MaterialTheme.typography.bodySmall,
                    color = if (warn != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Light / medium / aggressive list for how eagerly the host stops cached guest processes. */
/**
 * Added for AEmulator Plus (2026-11-19). Storage the guest reports: a size slider (off = the phone's own disk, then the presets)
 * and the flash type. Free space is always the phone's real free space, see [StorageSpoof].
 */
@Composable
private fun StoragePicker(s: VmSettings, set: (VmSettings) -> Unit) {
    val steps = listOf(0) + StorageSpoof.PRESETS_GB
    val idx = steps.indexOf(s.storageGb).coerceAtLeast(0)
    var typeMenu by remember { mutableStateOf(false) }
    val type = StorageType.parse(s.storageType)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_storage)) },
        supportingContent = {
            Column {
                Text(if (s.storageGb == 0) stringResource(R.string.vs_storage_auto) else StorageSpoof.text(s.storageGb))
                Slider(value = idx.toFloat(), onValueChange = { set(s.copy(storageGb = steps[it.roundToInt().coerceIn(0, steps.lastIndex)])) },
                    valueRange = 0f..steps.lastIndex.toFloat(), steps = steps.size - 2)
                Text(stringResource(R.string.vs_storage_sub), style = MaterialTheme.typography.bodySmall)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_storage_type)) },
        supportingContent = { Text(stringResource(R.string.vs_storage_type_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { typeMenu = true }) { Text(type?.label ?: stringResource(R.string.vs_storage_type_auto)) }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.vs_storage_type_auto)) }, onClick = { typeMenu = false; set(s.copy(storageType = "")) })
                    StorageType.entries.forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) }, onClick = { typeMenu = false; set(s.copy(storageType = e.id)) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Added for AEmulator Plus (2026-11-21). The memory type the guest reports (LPDDR2 ... LPDDR5X); the amount stays in "Guest RAM". */
@Composable
private fun RamTypePicker(s: VmSettings, set: (VmSettings) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val type = RamType.parse(s.ramType)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_ram_type)) },
        supportingContent = { Text(stringResource(R.string.vs_ram_type_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { menu = true }) { Text(type?.label ?: stringResource(R.string.vs_ram_type_auto)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.vs_ram_type_auto)) }, onClick = { menu = false; set(s.copy(ramType = "")) })
                    RamType.entries.forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) }, onClick = { menu = false; set(s.copy(ramType = e.id)) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun MemoryModePicker(mode: MemoryMode, set: (MemoryMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(m: MemoryMode) = when (m) {
        MemoryMode.LIGHT -> R.string.vs_memory_light
        MemoryMode.MEDIUM -> R.string.vs_memory_medium
        MemoryMode.AGGRESSIVE -> R.string.vs_memory_aggressive
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_memory_mode)) },
        supportingContent = {
            Text(stringResource(when (mode) {
                MemoryMode.LIGHT -> R.string.vs_memory_light_sub
                MemoryMode.MEDIUM -> R.string.vs_memory_medium_sub
                MemoryMode.AGGRESSIVE -> R.string.vs_memory_aggressive_sub
            }))
        },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(stringResource(label(mode))) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    MemoryMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(stringResource(label(m))) }, onClick = { open = false; set(m) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Disabled / 0° / 180° / no-gyroscope list for the host motion sensors. */
@Composable
private fun GyroLockPicker(mode: Int, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = listOf(R.string.vs_gyro_disabled, R.string.vs_gyro_0, R.string.vs_gyro_180, R.string.vs_gyro_none)
        .map { stringResource(it) }
    val current = GyroLock.sanitize(mode)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_gyro_lock)) },
        supportingContent = { Text(stringResource(R.string.vs_gyro_lock_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(labels[current]) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    labels.forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; set(i) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Radio technology the fake modem reports (2G ... LTE); only the values the firmware's API level knows are offered. */
@Composable
private fun NetworkTypePicker(tech: Int, api: Int, cdma: Boolean, lteOnCdma: Boolean, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_network)) },
        supportingContent = { Text(stringResource(R.string.vs_network_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(NetworkType.label(NetworkType.sanitize(tech, api, cdma, lteOnCdma))) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    NetworkType.available(api, cdma, lteOnCdma).forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) }, onClick = { open = false; set(e.tech) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Added for AEmulator Plus (2026-11-19). The chip the guest reports. A three-item menu: Preset (opens a window with every chip
 * of the image's instruction set, see [SocPresetTable]), Custom (every value editable) and Host (the phone's own chip). Blank
 * (the stored default) is Yellowstone on a Project Tango image and the host's chip on any other. See [SocProfiles].
 */
@Composable
private fun SocPicker(img: GuestImage, s: VmSettings, set: (VmSettings) -> Unit) {
    val ctx = LocalContext.current
    val tango = remember(img.id) { SocProfiles.isTangoTree(app.aemu.core.VmPaths(ctx, img.id).root, img.model, img.name) }
    val isa = remember(img.arch) { SocProfiles.isaOf(img.arch) }
    var open by remember { mutableStateOf(false) }
    var window by remember { mutableStateOf(false) }
    val id = SocProfiles.effectiveId(s.soc, tango, isa)
    val isHost = id == SocProfiles.HOST
    val isCustom = id == SocProfiles.CUSTOM
    val tangoDefault = stringResource(R.string.vs_soc_default, SocProfiles.YELLOWSTONE_SPEC.label)
    val modeLabel = stringResource(when { isHost -> R.string.vs_soc_host; isCustom -> R.string.vs_soc_custom; else -> R.string.vs_soc_preset })
    val chosen: SocSpec? = if (id == SocProfiles.YELLOWSTONE) SocProfiles.YELLOWSTONE_SPEC else SocProfiles.preset(id)
    // what "custom" starts from: the chip that is reported now, else the first chip of this instruction set
    fun seed(): SocSpec = (s.socCustom?.takeIf { it.isa == isa } ?: chosen?.takeIf { it.isa == isa } ?: SocProfiles.presetsFor(isa).let { l -> l.firstOrNull { it.id == "sm8150" } ?: l.first() })
        .copy(id = SocProfiles.CUSTOM, label = "Custom")
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_soc)) },
        supportingContent = { Text(stringResource(R.string.vs_soc_sub)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(modeLabel, modifier = Modifier.weight(1f), maxLines = 1)
            Text("▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vs_soc_preset), fontWeight = if (!isHost && !isCustom) FontWeight.Bold else null) },
                onClick = { open = false; window = true })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vs_soc_custom), fontWeight = if (isCustom) FontWeight.Bold else null) },
                onClick = { open = false; set(s.copy(soc = SocProfiles.CUSTOM, socCustom = seed())) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vs_soc_host), fontWeight = if (isHost) FontWeight.Bold else null) },
                onClick = { open = false; set(s.copy(soc = SocProfiles.HOST)) })
        }
    }
    if (!isHost && !isCustom) {
        // the chip now in force, in one line, and the way to the window again
        val shown = when {
            s.soc.isEmpty() && tango -> tangoDefault
            else -> SocProfiles.label(id, s.socCustom)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(shown, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            chosen?.let { Text(socSummary(it), style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { window = true }) { Text(stringResource(R.string.vs_soc_change)) }
        }
    }
    if (!s.gpu && (isCustom || (!isHost && chosen?.gpuRenderer?.isNotBlank() == true)))
        Text(stringResource(R.string.vs_soc_gpu_off), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    if (window) SocPresetWindow(
        presets = SocProfiles.presetsFor(isa),
        selected = if (!isHost && !isCustom && s.soc.isNotEmpty()) id else null,
        tangoDefault = if (tango) tangoDefault else null,
        tangoDefaultOn = tango && s.soc.isEmpty(),
        onPick = { p -> window = false; set(s.copy(soc = p.id)) },
        onDefault = { window = false; set(s.copy(soc = SocProfiles.DEFAULT)) },
        onDismiss = { window = false },
    )
    if (isCustom) {
        val spec = s.socCustom?.takeIf { it.isa == isa } ?: seed()
        fun put(v: SocSpec) = set(s.copy(soc = SocProfiles.CUSTOM, socCustom = v))
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SocField("Name", spec.label, 48) { put(spec.copy(label = it)) }
            SocField("ro.soc.manufacturer", spec.manufacturer, 32) { put(spec.copy(manufacturer = it)) }
            SocField("ro.soc.model", spec.model, 32) { put(spec.copy(model = it)) }
            if (isa == SocIsa.X86) {
                val x = spec.x86 ?: X86Cpu(model = 0, modelName = "Intel(R) Atom(TM) CPU")
                fun putX(v: X86Cpu) = put(spec.copy(x86 = v))
                SocField("/proc/cpuinfo model name", x.modelName, 80) { putX(x.copy(modelName = it)) }
                SocField("vendor_id", x.vendorId, 16) { putX(x.copy(vendorId = it)) }
                SocNumber("cpu family", x.family) { putX(x.copy(family = it)) }
                SocNumber("model", x.model) { putX(x.copy(model = it)) }
                SocNumber("stepping", x.stepping) { putX(x.copy(stepping = it)) }
                SocNumber("cache size (KB)", x.cacheKb) { putX(x.copy(cacheKb = it)) }
                SocNumber("cores", x.physicalCores) { c ->
                    val per = (spec.cores / x.physicalCores.coerceAtLeast(1)).coerceAtLeast(1)
                    put(spec.copy(x86 = x.copy(physicalCores = c), clusters = listOf(spec.clusters.first().copy(count = (c * per).coerceIn(1, SocProfiles.MAX_CORES)))))
                }
                SocNumber("threads per core", (spec.cores / x.physicalCores.coerceAtLeast(1)).coerceAtLeast(1)) { t ->
                    put(spec.copy(clusters = listOf(spec.clusters.first().copy(count = (x.physicalCores * t.coerceIn(1, 2)).coerceIn(1, SocProfiles.MAX_CORES)))))
                }
                SocNumber("max MHz", spec.clusters.first().maxMhz) { m -> put(spec.copy(clusters = listOf(spec.clusters.first().copy(maxMhz = m.coerceIn(100, 6000))))) }
                SocField("flags", x.flags, 800) { putX(x.copy(flags = it)) }
            } else {
                SocField("ro.board.platform", spec.platform, 32, token = true) { put(spec.copy(platform = it)) }
                SocField("/proc/cpuinfo Hardware", spec.hardware, 64) { put(spec.copy(hardware = it)) }
                SocField("soc0 family", spec.family, 32) { put(spec.copy(family = it)) }
                SocField("soc0 machine", spec.machine, 32) { put(spec.copy(machine = it)) }
                SocField("soc0 soc_id", spec.socId, 16) { put(spec.copy(socId = it)) }
                SocField("soc0 revision", spec.revision, 16) { put(spec.copy(revision = it)) }
                SocField("soc0 serial_number", spec.serialNumber, 24) { put(spec.copy(serialNumber = it)) }
                Toggle(stringResource(R.string.vs_soc_armv8), stringResource(R.string.vs_soc_armv8_sub), spec.arch >= 8) { put(spec.copy(arch = if (it) 8 else 7)) }
                // the layout is typed as text: it is only stored when every line parses
                var layout by remember { mutableStateOf(SocProfiles.encodeClusters(spec.clusters)) }
                val parsed = SocProfiles.parseClusters(layout)
                OutlinedTextField(
                    value = layout,
                    onValueChange = { v -> layout = v; SocProfiles.parseClusters(v)?.let { c -> put(spec.copy(clusters = c)) } },
                    label = { Text(stringResource(R.string.vs_soc_cores)) },
                    supportingText = { Text(stringResource(if (parsed == null) R.string.vs_soc_cores_bad else R.string.vs_soc_cores_sub)) },
                    isError = parsed == null, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                )
                SocField("Features", spec.features, 400, hint = stringResource(R.string.vs_soc_features_hint)) { put(spec.copy(features = it)) }
                SocField("BogoMIPS", spec.bogoMips, 12) { put(spec.copy(bogoMips = it)) }
            }
            SocField("GPU vendor (GL_VENDOR)", spec.gpuVendor, 64, hint = stringResource(R.string.vs_soc_gpu_hint)) { put(spec.copy(gpuVendor = it)) }
            SocField("GPU renderer (GL_RENDERER)", spec.gpuRenderer, 96, hint = stringResource(R.string.vs_soc_gpu_hint)) { put(spec.copy(gpuRenderer = it)) }
        }
    }
}

/** One line of what a preset reports: cores and top clock, GPU, process node. */
private fun socSummary(p: SocSpec): String {
    val cpu = if (p.isa == SocIsa.X86 && p.x86 != null) {
        val threads = p.cores
        (if (threads > p.x86.physicalCores) "${p.x86.physicalCores} cores / $threads threads" else "${p.cores} cores") + " · ${p.clusters.maxOf { it.maxMhz }} MHz"
    } else "${p.cores} cores · ${p.clusters.maxOf { it.maxMhz }} MHz"
    return listOf(cpu, p.gpuRenderer.removePrefix("Mesa DRI ").removeSuffix("/PCIe/SSE2"), p.note).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * The preset window: a large dialog with a search box over the list of chips (name, cores, clock, GPU, process node).
 * Tapping a chip chooses it and closes the window.
 */
@Composable
private fun SocPresetWindow(
    presets: List<SocSpec>, selected: String?, tangoDefault: String?, tangoDefaultOn: Boolean,
    onPick: (SocSpec) -> Unit, onDefault: () -> Unit, onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, presets) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) presets else presets.filter { p -> q.split(' ').all { w -> (p.label + " " + p.model + " " + p.gpuRenderer + " " + p.platform).lowercase().contains(w) } }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vs_soc_preset_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.vs_soc_close)) }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                    label = { Text(stringResource(R.string.vs_soc_search)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                    if (tangoDefault != null && query.isBlank()) item(key = "tango-default") {
                        SocPresetRow(tangoDefault, stringResource(R.string.vs_soc_default_sub), tangoDefaultOn, onDefault)
                    }
                    items(shown.size, key = { shown[it].id }) { i ->
                        val p = shown[i]
                        SocPresetRow(p.label, socSummary(p), p.id == selected) { onPick(p) }
                    }
                    if (shown.isEmpty()) item(key = "none") {
                        Text(stringResource(R.string.vs_soc_none), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SocPresetRow(title: String, sub: String, on: Boolean, click: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = if (on) FontWeight.Bold else null) },
        supportingContent = { Text(sub) },
        trailingContent = if (on) ({ Text("✓") }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = click),
    )
}

/** A whole number of the custom x86 chip; letters are ignored. */
@Composable
private fun SocNumber(label: String, value: Int, set: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { v -> v.filter { it.isDigit() }.take(7).toIntOrNull()?.let(set) },
        label = { Text(label, maxLines = 1) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One text of the custom chip: printable ASCII, or (a property name) letters, digits and _ - . only. */
@Composable
private fun SocField(label: String, value: String, max: Int, token: Boolean = false, hint: String? = null, set: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> set(if (token) SocSpec.token(v, max) else v.filter { it in ' '..'~' }.take(max)) },
        label = { Text(label, maxLines = 1) },
        supportingText = if (hint != null) ({ Text(hint) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** ro.hardware given to the guest: free text plus a menu of common values; blank = the firmware's own. */
@Composable
private fun HardwarePicker(value: String, set: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(value = value, onValueChange = { set(VmSettings.cleanHardware(it)) },
            label = { Text(stringResource(R.string.vs_hardware)) }, placeholder = { Text(stringResource(R.string.vs_hardware_default)) },
            supportingText = { Text(stringResource(R.string.vs_hardware_sub)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.vs_hardware_pick)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.vs_hardware_default)) }, onClick = { open = false; set("") })
                VmSettings.HARDWARE_PRESETS.forEach { hw ->
                    DropdownMenuItem(text = { Text(hw) }, onClick = { open = false; set(hw) })
                }
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: Int, modifier: Modifier, set: (Int) -> Unit) {
    // The setter rounds/clamps (width and height are forced even, dpi is clamped), so the value that comes back
    // can differ from what was typed: 5 -> 4, 9 -> 8, or a lone "1" -> 2. While the field has focus the typed text
    // must stay as typed; it is only replaced by the stored value once focus leaves (or the value changes elsewhere,
    // e.g. a preset is picked).
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }
    OutlinedTextField(value = text, onValueChange = { v -> text = v.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(set) },
        label = { Text(label) }, singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

/** Delta (incremental) OTA zips applied on top of this VM's system image. */
@Composable
private fun OtaSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prepared by remember { mutableStateOf<app.aemu.core.DeltaOta.Prepared?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { prepared?.close() } }
    val pick = rememberSingleFilePicker { uri ->
        picking = false
        if (uri == null) return@rememberSingleFilePicker
        setBusy(true)
        message = ctx.getString(R.string.vs_ota_checking)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.aemu.core.DeltaOta.prepare(ctx, img.id, uri) } }
            setBusy(false)
            result.onSuccess { prepared?.close(); prepared = it; message = null }
                .onFailure { message = ctx.getString(R.string.vs_ota_failed, it.userMessage(ctx)) }
        }
    }
    Text(stringResource(R.string.vs_ota), style = MaterialTheme.typography.titleMedium)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_ota_install)) },
        supportingContent = { Text(message ?: stringResource(R.string.vs_ota_sub)) },
        trailingContent = {
            TextButton(enabled = !busy && !picking, onClick = { picking = true; pick.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.vs_ota_button))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    prepared?.let { p ->
        AlertDialog(
            onDismissRequest = { p.close(); prepared = null },
            title = { Text(stringResource(R.string.vs_ota_confirm_title)) },
            text = {
                val base = stringResource(R.string.vs_ota_confirm, p.fromBuild, p.toBuild)
                Text(if (p.partial) base + "\n\n" + stringResource(R.string.vs_ota_partial) else base)
            },
            dismissButton = { TextButton(onClick = { p.close(); prepared = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                prepared = null
                setBusy(true)
                message = ctx.getString(R.string.vs_ota_working, "")
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            p.use { app.aemu.core.DeltaOta.install(ctx, img.id, it) { stage ->
                                message = ctx.getString(R.string.vs_ota_working, stage.text(ctx))
                            } }
                        }
                    }
                    setBusy(false)
                    message = result.fold(
                        { ctx.getString(R.string.vs_ota_done, it.toBuild, it.patched, it.added, it.removed) +
                            it.notes.joinToString("") { n -> "\n" + n.text(ctx) } },
                        { ctx.getString(R.string.vs_ota_failed, it.userMessage(ctx)) },
                    )
                }
            }) { Text(stringResource(R.string.vs_ota_confirm_ok)) } },
        )
    }
}
