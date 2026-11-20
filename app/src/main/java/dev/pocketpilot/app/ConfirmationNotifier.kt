package dev.pocketpilot.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.pocketpilot.core.Pending

/**
 * Destructive actions are never executed silently. The engine parks them and we ask the human,
 * both as a notification and as an in-app dialog.
 */
object ConfirmationNotifier {
    private const val CHANNEL = "pilot_confirm"

    fun show(context: Context, pending: Pending) {
        LogBus.add("confirmation needed: ${pending.id} ${pending.reason}")
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            LogBus.add("notifications blocked; approve from the PocketPilot app")
            return
        }
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Action confirmations", NotificationManager.IMPORTANCE_HIGH)
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val approve = PendingIntent.getBroadcast(
                context,
                pending.id.hashCode() * 2,
                Intent(context, ConfirmReceiver::class.java)
                    .putExtra("id", pending.id)
                    .putExtra("approve", true),
                flags
            )
            val deny = PendingIntent.getBroadcast(
                context,
                pending.id.hashCode() * 2 + 1,
                Intent(context, ConfirmReceiver::class.java)
                    .putExtra("id", pending.id)
                    .putExtra("approve", false),
                flags
            )
            val open = PendingIntent.getActivity(
                context,
                9,
                Intent(context, MainActivity::class.java),
                flags
            )
            val detail = buildString {
                append(pending.reason.replace('_', ' '))
                pending.detail?.let { append(": ").append(it) }
                append("\n").append(pending.tool)
                pending.packageName?.let { append(" in ").append(it) }
            }
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Approve action?")
                .setContentText(detail)
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_menu_send,
                        "Approve",
                        approve
                    ).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Deny",
                        deny
                    ).build()
                )
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            manager.notify(pending.id.hashCode(), notification)
        } catch (t: Throwable) {
            LogBus.add("notification failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}
