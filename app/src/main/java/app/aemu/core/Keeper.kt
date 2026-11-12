/* Modified for AEmulator Plus, 2026-11-09: one Keeper per VM process (Keeper1..3 for the extra slots), its own notification,
 * and a SHUTDOWN command that works without the machine's window. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.aemu.R
import app.aemu.ui.VmHost

/**
 * Служба переднего плана процесса :vm (у каждой машины свой процесс и своя служба). Пока она жива, Android не выгружает
 * процесс с прошивкой при сворачивании и не режет ему процессор.
 */
open class Keeper : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == SHUTDOWN) {
            // asked from a tab or the library: turn the machine off and take the process with it
            VmHost.shutdown(applicationContext)
            return START_NOT_STICKY
        }
        val title = i?.getStringExtra("title") ?: "Android"
        runCatching { startForeground(noteId(VmSlots.current()), note(this, title)) }
        if (i?.action == STOP) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "vm"
        private const val ID = 1
        const val STOP = "app.aemu.KEEPER_STOP"
        const val SHUTDOWN = "app.aemu.KEEPER_SHUTDOWN"

        private fun noteId(slot: Int) = ID + slot

        fun ensureChannel(ctx: Context) {
            runCatching {
                ctx.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(CH, ctx.getString(R.string.channel_vm), NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun note(ctx: Context, title: String): Notification {
            ensureChannel(ctx)
            val slot = VmSlots.current()
            val intent = Intent(ctx, VmSlots.activityClass(slot))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open = PendingIntent.getActivity(ctx, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CH)
                .setContentTitle(ctx.getString(R.string.vm_running, title))
                .setContentText(ctx.getString(R.string.vm_running_hint))
                .setSmallIcon(R.drawable.ic_stat_vm)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }

        /** called inside a VM process: its own Keeper */
        fun hold(ctx: Context, title: String) {
            runCatching { ctx.startForegroundService(Intent(ctx, VmSlots.keeperClass(VmSlots.current())).putExtra("title", title)) }
        }

        fun release(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, VmSlots.keeperClass(VmSlots.current()))) }
        }

        /** from any process: shuts the machine of [slot] down (its Keeper does it in the machine's own process) */
        fun requestShutdown(ctx: Context, slot: Int) {
            runCatching { ctx.startService(Intent(ctx, VmSlots.keeperClass(slot)).setAction(SHUTDOWN)) }
        }
    }
}
