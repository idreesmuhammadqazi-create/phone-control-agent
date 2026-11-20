package dev.pocketpilot.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketpilot.core.Pending

class MainActivity : Activity() {

    private val app: PilotApplication get() = application as PilotApplication
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var hintView: TextView
    private lateinit var serverButton: Button
    private lateinit var agentButton: Button
    private lateinit var goalInput: EditText

    private var confirmDialog: AlertDialog? = null
    private var confirmDialogId: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        buildUi()
        if (app.settings.autoStartServer && !PilotService.serverRunning) {
            try {
                PilotService.start(this, PilotService.ACTION_START_SERVER)
            } catch (t: Throwable) {
                LogBus.add("start failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        confirmDialog?.dismiss()
        super.onDestroy()
    }

    // ------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(
            TextView(this).apply {
                text = "PocketPilot"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            },
            lp()
        )
        root.addView(
            TextView(this).apply {
                text = "Accessibility-driven phone control for AI agents. No screenshots, no vision."
                textSize = 12.5f
                setTextColor(Color.DKGRAY)
            },
            lp()
        )

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(statusView, lp())

        root.addView(button("Enable accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lp())

        serverButton = button("Start server") {
            if (PilotService.serverRunning) {
                PilotService.start(this, PilotService.ACTION_STOP_SERVER)
            } else {
                PilotService.start(this, PilotService.ACTION_START_SERVER)
            }
        }
        root.addView(serverButton, lp())

        root.addView(button("Server & safety settings") { showServerDialog() }, lp())
        root.addView(button("AI settings (on-device agent)") { showAiDialog() }, lp())

        hintView = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.rgb(70, 70, 70))
            setPadding(0, dp(2), 0, dp(6))
            setTextIsSelectable(true)
        }
        root.addView(hintView, lp())

        root.addView(View(this).apply { setBackgroundColor(Color.rgb(224, 224, 224)) }, lp(1))

        root.addView(
            TextView(this).apply {
                text = "On-device agent"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(2))
            },
            lp()
        )
        goalInput = EditText(this).apply {
            hint = "Goal, e.g. Turn on Wi-Fi and connect to HomeNet"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(false)
            minLines = 2
        }
        root.addView(goalInput, lp())

        agentButton = button("Run agent") { toggleAgent() }
        root.addView(agentButton, lp())

        root.addView(
            TextView(this).apply {
                text = "Activity"
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(14), 0, dp(2))
            },
            lp()
        )
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(Color.rgb(40, 40, 40))
            text = "(no activity yet)"
            setTextIsSelectable(true)
        }
        root.addView(logView, lp())

        root.addView(
            TextView(this).apply {
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, dp(18), 0, 0)
                text = "Safety: destructive actions (payments, deletes, password fields) require " +
                    "explicit approval. Sensitive text is redacted from observations."
            },
            lp()
        )
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun lp(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            setMargins(0, dp(4), 0, dp(4))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ refresh

    private fun refresh() {
        val accessibility = PilotAccessibilityService.instance != null
        val serverRunning = PilotService.serverRunning
        val agent = app.agent.state
        val sb = StringBuilder()
        sb.append(if (accessibility) "Accessibility: ON" else "Accessibility: OFF")
        sb.append("    Server: ").append(if (serverRunning) "ON :${PilotService.serverPort}" else "OFF")
        sb.append("\nAgent: ").append(agent.status)
        if (agent.running) sb.append("  step ").append(agent.step).append('/').append(agent.maxSteps)
        if (!accessibility) sb.append("\nEnable PocketPilot automation in Accessibility settings to start.")
        PilotService.lastError?.let { sb.append("\nServer error: ").append(it.take(140)) }
        agent.error?.let { sb.append("\nAgent error: ").append(it.take(140)) }
        statusView.text = sb.toString()
        statusView.setTextColor(if (accessibility) Color.rgb(0, 100, 0) else Color.rgb(170, 0, 0))

        serverButton.text = if (serverRunning) "Stop server" else "Start server"
        agentButton.text = if (agent.running) "Stop agent" else "Run agent"

        val url = "http://127.0.0.1:${app.settings.port}/mcp"
        hintView.text = "From a computer:\n" +
            "adb forward tcp:${app.settings.port} tcp:${app.settings.port}\n" +
            "MCP endpoint: $url"

        val log = LogBus.snapshot().takeLast(14).joinToString("\n")
        if (log.isNotEmpty() && logView.text.toString() != log) logView.text = log

        val pending = app.engine.pendingConfirmations()
        if (pending.isEmpty() && app.agent.state.status == "awaiting_confirmation") {
            app.agent.clearAwaitingConfirmation()
        }
        val shownId = confirmDialogId
        if (shownId != null && pending.none { it.id == shownId }) {
            // The approval expired or was resolved elsewhere; do not leave a dead dialog up.
            confirmDialog?.dismiss()
        } else if (pending.isNotEmpty()) {
            showConfirm(pending.first())
        }
    }

    private fun showConfirm(pending: Pending) {
        if (confirmDialog?.isShowing == true) return
        confirmDialogId = pending.id
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("Approve ${pending.reason.replace('_', ' ')}?")
            .setMessage(
                "${pending.tool}${pending.packageName?.let { " in $it" } ?: ""}\n\n" +
                    (pending.detail ?: "This action may be irreversible.")
            )
            .setPositiveButton("Approve") { _, _ ->
                Thread {
                    val result = app.engine.confirm(pending.id, true)
                    app.agent.onConfirmationResolved(result.optBoolean("ok", false))
                    LogBus.add("approved ${pending.id}: ${result.optString("text")}")
                }.start()
            }
            .setNegativeButton("Deny") { _, _ ->
                Thread {
                    app.engine.confirm(pending.id, false)
                    app.agent.onConfirmationResolved(false)
                }.start()
            }
            .setOnDismissListener {
                confirmDialog = null
                confirmDialogId = null
            }
            .show()
    }

    // ------------------------------------------------------------ agent

    private fun toggleAgent() {
        if (app.agent.state.running) {
            app.agent.stop()
            return
        }
        val goal = goalInput.text.toString().trim()
        if (goal.isEmpty()) {
            toast("Enter a goal first")
            return
        }
        if (PilotAccessibilityService.instance == null) {
            toast("Enable the accessibility service first")
            return
        }
        if (app.settings.apiKey.isBlank() &&
            !app.settings.apiBase.startsWith("http://") &&
            !app.settings.apiBase.contains("127.0.0.1")
        ) {
            toast("Configure an AI provider in AI settings")
            return
        }
        PilotService.start(this, PilotService.ACTION_START_AGENT, goal, app.settings.agentSteps)
    }

    // ------------------------------------------------------------ dialogs

    private fun showServerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val portInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Port"
            setText(app.settings.port.toString())
        }
        val tokenInput = EditText(this).apply {
            hint = "Bearer token (optional, recommended)"
            setText(app.settings.token)
        }
        val lan = CheckBox(this).apply {
            text = "Allow LAN access (binds 0.0.0.0)"
            isChecked = app.settings.bindLan
        }
        val allowAll = CheckBox(this).apply {
            text = "Allow actions in all apps"
            isChecked = app.settings.allowAll
        }
        val autoStart = CheckBox(this).apply {
            text = "Start server when the app opens"
            isChecked = app.settings.autoStartServer
        }
        val allowList = EditText(this).apply {
            hint = "Allowlist: com.app.one, com.app.two"
            setText(app.settings.allowlist.joinToString(", "))
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT
        }

        layout.addView(label("Port"))
        layout.addView(portInput)
        layout.addView(label("Token"))
        layout.addView(tokenInput)
        layout.addView(lan)
        layout.addView(allowAll)
        layout.addView(autoStart)
        layout.addView(label("Allowlist (used when allow-all is off)"))
        layout.addView(allowList)

        AlertDialog.Builder(this)
            .setTitle("Server & safety")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Save") { _, _ ->
                app.settings.port = portInput.text.toString().toIntOrNull() ?: app.settings.port
                app.settings.token = tokenInput.text.toString()
                app.settings.bindLan = lan.isChecked
                app.settings.allowAll = allowAll.isChecked
                app.settings.autoStartServer = autoStart.isChecked
                app.settings.allowlist = allowList.text.toString()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                app.engine.guard.policy = app.settings.guardPolicy()
                LogBus.add("settings saved")
                if (PilotService.serverRunning) {
                    PilotService.start(this, PilotService.ACTION_RESTART_SERVER)
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAiDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val base = EditText(this).apply {
            hint = "https://api.openai.com/v1"
            setText(app.settings.apiBase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val model = EditText(this).apply {
            hint = "gpt-4o-mini"
            setText(app.settings.model)
        }
        val key = EditText(this).apply {
            hint = "API key"
            setText(app.settings.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val steps = EditText(this).apply {
            hint = "Max steps"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(app.settings.agentSteps.toString())
        }

        layout.addView(label("Base URL (OpenAI-compatible, include /v1)"))
        layout.addView(base)
        layout.addView(label("Model"))
        layout.addView(model)
        layout.addView(label("API key"))
        layout.addView(key)
        layout.addView(label("Max agent steps"))
        layout.addView(steps)

        AlertDialog.Builder(this)
            .setTitle("AI settings")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Save") { _, _ ->
                app.settings.apiBase = base.text.toString()
                app.settings.model = model.text.toString()
                app.settings.apiKey = key.text.toString()
                app.settings.agentSteps = steps.text.toString().toIntOrNull() ?: app.settings.agentSteps
                LogBus.add("AI settings saved")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(8), 0, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}
