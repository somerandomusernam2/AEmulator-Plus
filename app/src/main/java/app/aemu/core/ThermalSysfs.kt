package app.aemu.core

import java.io.File

/**
 * The guest's /sys/devices/virtual/thermal/thermal_zone17 (the zone Xiaomi's ThermalMonitorService reads directly).
 *
 * The runner serves a /sys path from the image's own tree when the file exists there and from the phone's real sysfs
 * otherwise, so with no file here the guest reads the phone's live sensor (73-85 C) and MQSThermalMonitor throws the
 * full-screen overheat overlay. The policy:
 *  - an explicit value ([VmSettings.thermalTemp], set from the machine's menu) always wins and is served as a file;
 *  - otherwise "thermal passthrough" off serves a constant cool [COOL_MILLI_C];
 *  - otherwise (passthrough on, nothing set) no file exists and the guest sees the phone's own reading.
 * The guest reads the file on every access, so a change made while the VM runs is seen at once.
 */
object ThermalSysfs {
    const val ZONE = "sys/devices/virtual/thermal/thermal_zone17"
    const val GUEST_PATH = "/sys/devices/virtual/thermal/thermal_zone17/temp"
    const val COOL_MILLI_C = 30000
    const val MIN_MILLI_C = -40000
    const val MAX_MILLI_C = 150000
    private const val FALLBACK_TYPE = "aemu-thermal"

    /**
     * Google TV Streamer's thermal HAL (android.hardware.thermal-service.pixel_for_kirkwood) reads its sensor and cooling
     * device names from a JSON config and abort()s ("ThermalHAL could not be initialized properly") when any of them is
     * missing from /sys/devices/virtual/thermal, taking IThermal/default with it; system_server (ThermalManagerService)
     * then waits for that service forever. The phone has no such zones, so the guest gets them here: the zone numbers
     * are high to stay clear of the phone's own, every zone reads the same cool value as zone 17 (or the user's value),
     * and the cooling devices sit at state 0.
     */
    private val EXTRA_ZONES = listOf("soc_max", "board_ddr", "board_wifi", "board_soc_bottom")
    private val EXTRA_COOLING = listOf("cpufreq-cpu0", "devfreq-13000000.gpu")
    private const val EXTRA_BASE = 100
    private const val THERMAL_DIR = "sys/devices/virtual/thermal"

    private fun put(f: File, text: String) {
        f.parentFile?.mkdirs()
        f.writeText(text)
        f.setReadable(true, false); f.setWritable(true, false)
    }

    private fun openDirs(root: File, dir: File) {
        var d: File? = dir
        while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile }
    }

    /**
     * Sensor and cooling device names the firmware's own thermal HAL config asks for (Pixel's HAL: the "Sensors" and
     * "CoolingDevices" arrays of vendor/etc/thermal_info_config*.json, each entry's "Name"). Pixel Watch (Wear 11)
     * wants PPG_NTC, SOC_NTC, AUDIO_NTC and BATT_NTC and aborts without any one of them; so the names are read from the
     * config the HAL will read instead of being guessed per device.
     */
    private fun configNames(root: File): Pair<List<String>, List<String>> {
        val zones = LinkedHashSet<String>(); val cooling = LinkedHashSet<String>()
        val dirs = listOf("vendor/etc", "system/vendor/etc", "odm/etc")
        for (d in dirs) File(root, d).listFiles()
            ?.filter { it.isFile && it.name.startsWith("thermal_info_config") && it.name.endsWith(".json") }
            ?.sortedBy { it.name }?.forEach { f ->
                runCatching {
                    val o = org.json.JSONObject(f.readText())
                    for ((key, into) in listOf("Sensors" to zones, "CoolingDevices" to cooling)) {
                        val a = o.optJSONArray(key) ?: continue
                        for (i in 0 until a.length()) a.optJSONObject(i)?.optString("Name")?.takeIf { it.isNotEmpty() }?.let { into.add(it) }
                    }
                }
            }
        return zones.toList() to cooling.toList()
    }

    private fun applyExtras(root: File, milli: Int) {
        val (cfgZones, cfgCooling) = configNames(root)
        val zoneNames = (EXTRA_ZONES + cfgZones).distinct()
        val coolingNames = (EXTRA_COOLING + cfgCooling).distinct()
        zoneNames.forEachIndexed { i, name ->
            val z = File(root, "$THERMAL_DIR/thermal_zone${EXTRA_BASE + i}")
            put(File(z, "type"), "$name\n")
            put(File(z, "temp"), "$milli\n")
            put(File(z, "policy"), "step_wise\n")
            openDirs(root, z)
        }
        coolingNames.forEachIndexed { i, name ->
            val c = File(root, "$THERMAL_DIR/cooling_device${EXTRA_BASE + i}")
            put(File(c, "type"), "$name\n")
            File(c, "cur_state").apply { if (!isFile) put(this, "0\n") }
            put(File(c, "max_state"), "10\n")
            openDirs(root, c)
        }
    }

    /** The value the guest is to see: null = the phone's real one (no file). */
    fun effective(s: VmSettings): Int? = s.thermalTemp?.coerceIn(MIN_MILLI_C, MAX_MILLI_C)
        ?: if (s.thermalPassthrough) null else COOL_MILLI_C

    /** Parses what the user typed: degrees Celsius ("30", "30.5") or millidegrees ("30000"); null = not a temperature. */
    fun parseInput(text: String): Int? {
        val t = text.trim().replace(',', '.').removeSuffix("°C").removeSuffix("C").trim()
        val d = t.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        // thermal sysfs counts in millidegrees; anything with a magnitude of 1000+ was typed that way already
        val milli = if (kotlin.math.abs(d) >= 1000) d else d * 1000
        return milli.toInt().takeIf { it in MIN_MILLI_C..MAX_MILLI_C }
    }

    fun format(milli: Int): String = String.format(java.util.Locale.ROOT, "%.1f °C", milli / 1000.0)

    private val hostZone = File("/sys/devices/virtual/thermal/thermal_zone17")

    /** The phone's own reading of the same zone, when this app may read it. */
    fun hostTemp(): Int? = runCatching { File(hostZone, "temp").readText().trim().toInt() }.getOrNull()

    private fun hostType(): String? = runCatching { File(hostZone, "type").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Makes the image's tree match [s]; safe to call while the guest runs. Returns the served value (null = the phone's). */
    fun apply(root: File, s: VmSettings, log: (String) -> Unit = {}): Int? {
        val want = effective(s)
        val dir = File(root, ZONE)
        runCatching {
            if (want == null) {
                File(dir, "temp").delete(); File(dir, "type").delete()
                log("thermal: zone 17 passes through the phone's sensor")
            } else {
                dir.mkdirs()
                var d: File? = dir
                while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile }
                File(dir, "temp").apply { writeText("$want\n"); setReadable(true, false); setWritable(true, false) }
                File(dir, "type").apply { if (!isFile) writeText((hostType() ?: FALLBACK_TYPE) + "\n"); setReadable(true, false) }
                log("thermal: zone 17 reads ${format(want)}${if (s.thermalTemp != null) " (set by the user)" else ""}")
            }
        }.onFailure { log("thermal: cannot update zone 17: ${it.message}") }
        runCatching { applyExtras(root, want ?: COOL_MILLI_C) }
            .onFailure { log("thermal: cannot write the HAL's zones: ${it.message}") }
        return want
    }

    /** What the guest sees right now (the file when there is one, else the phone's reading). */
    fun current(root: File): Int? =
        runCatching { File(root, "$ZONE/temp").readText().trim().toInt() }.getOrNull() ?: hostTemp()
}
