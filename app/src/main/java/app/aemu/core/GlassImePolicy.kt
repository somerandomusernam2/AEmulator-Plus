package app.aemu.core

/**
 * Glass XE 4.4.x (API 19): on the first boot InputMethodManagerService finds no selected IME
 * ("No IME selected. Choose the most applicable IME.") and its constructor throws a NullPointerException in
 * resetDefaultImeLocked ("BOOT FAILURE starting Input Manager Service"). The "input_method" service is then never
 * registered, a window in system_server gets focus and InputMethodManager.startInputInner dies with a
 * NullPointerException in the system process, which takes zygote down (code 137).
 *
 * With default_input_method already set the constructor takes the "IME selected on boot" branch and never calls
 * resetDefaultImeLocked. The value is written into settings.db (which exists once SettingsProvider has run once).
 */
object GlassImePolicy {
    const val REMOTE_IME = "com.google.glass.remoteime/.RemoteImeService"
    const val FAILURE_MARK = "BOOT FAILURE starting Input Manager Service"

    /** value to store, or null when the setting is already there or the IME is not installed */
    fun valueToSeed(current: String?, remoteImeInstalled: Boolean): String? =
        if (current.isNullOrBlank() && remoteImeInstalled) REMOTE_IME else null

    /** enabled_input_methods list with [ime] added (":"-separated, like the framework writes it) */
    fun enabledWith(current: String?, ime: String): String {
        val parts = current.orEmpty().split(':').filter { it.isNotBlank() }
        return if (ime in parts) parts.joinToString(":") else (parts + ime).joinToString(":")
    }
}
