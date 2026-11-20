package dev.pocketpilot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val approve = intent.getBooleanExtra("approve", false)
        val app = context.applicationContext as? PilotApplication ?: return
        val pending = goAsync()
        Thread {
            try {
                val result = app.engine.confirm(id, approve)
                LogBus.add("confirmation $id ${if (approve) "approved" else "denied"}: ${result.optString("text")}")
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(
                            context,
                            if (approve) "Action approved" else "Action denied",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
