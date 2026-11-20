package dev.pocketpilot.app

import android.app.Application
import dev.pocketpilot.core.EngineConfig
import dev.pocketpilot.core.Guard
import dev.pocketpilot.core.PilotEngine
import org.json.JSONObject

class PilotApplication : Application() {

    companion object {
        lateinit var instance: PilotApplication
            private set
    }

    lateinit var settings: Settings
    lateinit var engine: PilotEngine
    lateinit var agent: PilotAgent

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        engine = PilotEngine(AndroidDevice(this), Guard(settings.guardPolicy()), EngineConfig())
        agent = PilotAgent(this, engine, settings)
        engine.agentRunner = { goal, steps -> agent.runBlocking(goal, steps) }
        engine.logger = { message -> LogBus.add(message) }
        engine.onConfirmation = { pending -> ConfirmationNotifier.show(this, pending) }
        engine.statusExtra = {
            JSONObject()
                .put("port", settings.port)
                .put("lan", settings.bindLan)
                .put("server", PilotService.serverRunning)
                .put("agent", agent.state.status)
                .put("agentStep", agent.state.step)
                .put("ai", settings.apiKey.isNotEmpty() || settings.apiBase.startsWith("http://"))
        }
        LogBus.add("PocketPilot ${BuildConfig.VERSION_NAME} started")
    }

    fun onAccessibilityReady() {
        LogBus.add("engine ready")
        if (settings.autoStartServer && !PilotService.serverRunning) {
            try {
                PilotService.start(this, PilotService.ACTION_START_SERVER)
            } catch (t: Throwable) {
                LogBus.add("autostart failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}
