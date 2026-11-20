package dev.pocketpilot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.pocketpilot.core.HttpServer
import dev.pocketpilot.core.Router

class PilotService : Service() {

    companion object {
        const val ACTION_START_SERVER = "dev.pocketpilot.action.START_SERVER"
        const val ACTION_RESTART_SERVER = "dev.pocketpilot.action.RESTART_SERVER"
        const val ACTION_STOP_SERVER = "dev.pocketpilot.action.STOP_SERVER"
        const val ACTION_START_AGENT = "dev.pocketpilot.action.START_AGENT"
        const val ACTION_STOP_ALL = "dev.pocketpilot.action.STOP_ALL"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_STEPS = "steps"

        @Volatile
        var serverRunning: Boolean = false
            private set

        @Volatile
        var serverPort: Int = 0
            private set

        @Volatile
        var lastError: String? = null
            private set

        private const val CHANNEL = "pilot_service"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, action: String, goal: String? = null, steps: Int = 0) {
            val intent = Intent(context, PilotService::class.java).setAction(action)
            if (goal != null) intent.putExtra(EXTRA_GOAL, goal)
            if (steps > 0) intent.putExtra(EXTRA_STEPS, steps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var server: HttpServer? = null
    private var fingerprint: String? = null
    private val app: PilotApplication get() = application as PilotApplication

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "PocketPilot service", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID, buildNotification("Starting"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> startServer()
            ACTION_RESTART_SERVER -> startServer()
            ACTION_STOP_SERVER -> {
                stopServer()
                if (!app.agent.state.running) stopSelf()
            }
            ACTION_START_AGENT -> {
                val goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                val steps = intent.getIntExtra(EXTRA_STEPS, app.settings.agentSteps)
                startServer()
                if (goal.isNotBlank()) app.agent.start(goal, steps)
            }
            ACTION_STOP_ALL -> {
                stopServer()
                app.agent.stop()
                stopSelf()
            }
            else -> startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        app.agent.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        val settings = app.settings
        val print = "${settings.port}|${settings.bindLan}|${settings.token.hashCode()}"
        if (serverRunning && server != null && fingerprint == print) {
            updateNotification("Listening on ${if (settings.bindLan) "0.0.0.0" else "127.0.0.1"}:${settings.port}")
            return
        }
        stopServer()
        val router = Router(
            engine = app.engine,
            token = settings.token.takeIf { it.isNotBlank() }
        )
        val instance = HttpServer(settings.port, settings.bindLan, router) { LogBus.add(it) }
        val error = instance.start()
        if (error == null) {
            server = instance
            serverRunning = true
            serverPort = settings.port
            fingerprint = print
            lastError = null
            val host = if (settings.bindLan) "0.0.0.0" else "127.0.0.1"
            updateNotification("Listening on $host:${settings.port} (MCP at /mcp)")
        } else {
            serverRunning = false
            lastError = error
            updateNotification("Server error: $error")
            LogBus.add("server error: $error")
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        serverRunning = false
        fingerprint = null
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PilotService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("PocketPilot")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stop
                ).build()
            )
            .build()
    }
}
