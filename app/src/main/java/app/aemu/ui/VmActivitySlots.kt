/* Added for AEmulator Plus, 2026-11-09: one activity class per VM process (a manifest process is fixed per class). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

class VmActivity1 : VmActivity()
class VmActivity2 : VmActivity()
class VmActivity3 : VmActivity()

/* The same windows with a translucent theme (see VmSlots.activityClass): the machine on top in a tab of two machines. */
class VmOverlayActivity : VmActivity() { override val overlayVariant get() = true }
class VmOverlayActivity1 : VmActivity() { override val overlayVariant get() = true }
class VmOverlayActivity2 : VmActivity() { override val overlayVariant get() = true }
class VmOverlayActivity3 : VmActivity() { override val overlayVariant get() = true }
