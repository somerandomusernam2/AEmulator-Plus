/* AEmulator Plus addition, 2026-11-05. GPL-3.0; see LICENSE. */
package app.aemu.core

/**
 * Android 4.x (Dalvik) boot deadlock between system_server's ActivityManager thread and the keyguard in SystemUI:
 *
 *   ActivityManager (holds the ActivityManagerService lock), in systemReady -> startHomeActivityLocked ->
 *     ActivityStackSupervisor.updateCascadeHiddenFlag -> WindowManagerService.isKeyguardSecure ->
 *     PhoneWindowManager.isKeyguardSecure -> KeyguardServiceWrapper.isSecure -> IKeyguardService.isSecure  (binder call to SystemUI)
 *   SystemUI's keyguard answers that call by asking system_server for the current user (IActivityManager.getCurrentUser,
 *     which is synchronized on the same ActivityManagerService lock) -> waits for the lock for ever.
 *
 * The same lock then blocks every other binder thread (attachApplication, registerReceiver ...), the Watchdog fires
 * after 60 s ("Blocked in handler on ActivityManager"), zygote exits with 137 and the screen stays black. On a phone
 * the keyguard usually connects after systemReady has returned, so the call finds no service and returns false; with
 * the emulator's timing SystemUI is up first and the call really goes over binder.
 *
 * The same cycle exists on a second path, seen on the TouchWiz (Samsung) 4.4 image once isSecure() was cut:
 *
 *   binder thread (holds the ActivityManagerService lock), ActivityManagerService.activityPaused -> ... ->
 *     ActivityStackSupervisor.resumeHomeActivity -> WindowManagerService.moveTaskToTop -> performLayoutAndPlaceSurfaces ->
 *     PhoneWindowManager.finishPostLayoutPolicyLw -> needHideTrayBar -> KeyguardServiceDelegate.isShowing ->
 *     KeyguardServiceWrapper.isShowing -> IKeyguardService.isShowing  (binder call to SystemUI, transaction code 1)
 *   SystemUI's threads, meanwhile, wait on system_server (binderd: SystemUI -> system_server transactions pending),
 *   which is stuck behind the same lock. main, android.fg (Watchdog monitor), ActivityManager, android.bg and
 *   all binder threads queue up on it, the Watchdog kills system_server and zygote exits with 137 again.
 *
 * The cycle is cut on the system_server side: the synchronous boolean queries of [CLASS_DESC] return false at once
 * (`const/4 v0, 0; return v0`), so no binder call is made while the ActivityManagerService lock is held. These are
 * [METHOD] (required, the class is not touched without it), and [OPTIONAL_METHODS], the siblings that read the same
 * keyguard state ("is it showing", "is it showing and not hidden") and so have to stay consistent with each other;
 * a class that has no such method is simply left alone for it. The keyguard itself still locks and unlocks as before;
 * only the framework's own "is the keyguard secure / showing" queries (window-cascade, capture and tray-bar decisions)
 * now read "no" on these old images. (isInputRestricted() and isDismissable() are the other synchronous boolean queries;
 * they are left alone until a trace shows them under a lock, add them to [OPTIONAL_METHODS] if one does.)
 *
 * Only the dex inside the jar is edited (same zip rewrite and header fix-up as [WatchdogPatch]); the firmware's
 * .odex, if any, no longer matches and is ignored. No Android classes here, so it runs on a plain JVM.
 */
object KeyguardSecurePatch {
    const val CLASS_DESC = "Lcom/android/internal/policy/impl/keyguard/KeyguardServiceWrapper;"
    const val METHOD = "isSecure"

    /** Sibling queries patched the same way when the class has them (see the class comment). */
    val OPTIONAL_METHODS = listOf("isShowing", "isShowingAndNotHidden")

    private const val CONST4: Byte = 0x12
    private const val RETURN: Byte = 0x0f

    private fun isDex(d: ByteArray) = d.size >= 112 && d[0] == 'd'.code.toByte() && d[1] == 'e'.code.toByte() &&
        d[2] == 'x'.code.toByte() && d[3] == '\n'.code.toByte()

    /**
     * Makes boolean [name]() of [CLASS_DESC] return false at once in [d] (a plain dex, parsed as [x]), in place. A method that
     * is [required] but missing is UNEXPECTED; an optional one that is missing is ABSENT (nothing to do).
     */
    private fun patchMethod(d: ByteArray, x: DexTools.Dex, name: String, required: Boolean): WatchdogPatch.Result {
        val codes = try { DexTools.methodCode(x, name, CLASS_DESC, returns = "Z") } catch (e: RuntimeException) {
            return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "unreadable class data: ${e.message}")
        } ?: return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT)
        if (codes.isEmpty() && !required) return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT)
        if (codes.size != 1) return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "${codes.size} boolean $name() methods with code in the class")
        val code = codes[0]
        val registers = DexTools.u2(d, code)
        val insnsSize = DexTools.u4(d, code + 12)
        val start = code + 16
        if (registers < 1) return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "$name(): no register for the result")
        if (insnsSize < 2 || start + 2L * insnsSize > d.size) return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "$name(): code item too short or out of range")
        if (d[start] == CONST4 && d[start + 1] == 0.toByte() && d[start + 2] == RETURN && d[start + 3] == 0.toByte())
            return WatchdogPatch.Result(WatchdogPatch.Outcome.ALREADY)
        // Whole instructions only: the two new units replace the first instruction(s) covering at least two code units, and
        // any unit left over from the last replaced instruction becomes a nop (an orphaned half instruction breaks the verifier's walk).
        var units = 0
        while (units < 2) {
            val len = DexTools.insnLength(d[start + 2 * units].toInt() and 0xff)
            if (units + len > insnsSize) return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "$name(): first instructions run past the code item")
            units += len
        }
        d[start] = CONST4; d[start + 1] = 0; d[start + 2] = RETURN; d[start + 3] = 0
        for (k in 4 until 2 * units) d[start + k] = 0
        DexTools.fixHeader(d)
        return WatchdogPatch.Result(WatchdogPatch.Outcome.PATCHED)
    }

    /**
     * Patches [d] (a plain dex) in place; the outcomes are those of [WatchdogPatch]. [METHOD] decides whether the class is
     * ours: when it is absent or not in the expected shape nothing at all is changed. Otherwise the result is PATCHED when any
     * of the methods changed, else ALREADY when any was patched before, else UNEXPECTED when an optional one is odd.
     */
    fun patchDex(d: ByteArray): WatchdogPatch.Result {
        if (!isDex(d)) return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT, "not a dex")
        val x = try { DexTools.Dex(d) } catch (e: RuntimeException) {
            return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "unreadable header: ${e.message}")
        }
        val first = patchMethod(d, x, METHOD, required = true)
        if (first.outcome == WatchdogPatch.Outcome.ABSENT || first.outcome == WatchdogPatch.Outcome.UNEXPECTED) return first
        val rs = listOf(first) + OPTIONAL_METHODS.map { patchMethod(d, x, it, required = false) }
        for (o in listOf(WatchdogPatch.Outcome.PATCHED, WatchdogPatch.Outcome.ALREADY, WatchdogPatch.Outcome.UNEXPECTED))
            rs.firstOrNull { it.outcome == o }?.let { return it }
        return first
    }

    /** Patches every classes*.dex of [jar] that has the class; rewrites the jar only when something changed. */
    fun patchJar(jar: java.io.File, backup: java.io.File?): WatchdogPatch.Result =
        WatchdogPatch.patchJar(jar, backup) { patchDex(it) }

    /** Whether [jar] is exactly the stock jar [stock] with this patch applied and nothing else (true when the check cannot be made). */
    fun isStockPlusPatch(jar: java.io.File, stock: java.io.File): Boolean {
        val tmp = java.io.File(jar.parentFile, jar.name + ".aemu-chk")
        return try {
            stock.copyTo(tmp, overwrite = true)
            patchJar(tmp, null)
            tmp.readBytes().contentEquals(jar.readBytes())
        } catch (e: Exception) {
            true
        } finally {
            tmp.delete()
        }
    }
}
