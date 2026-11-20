package dev.pocketpilot.app

import android.content.Context
import dev.pocketpilot.core.GuardPolicy
import org.json.JSONObject

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("pocketpilot", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", 8787).coerceIn(1024, 65535)
        set(value) = prefs.edit().putInt("port", value.coerceIn(1024, 65535)).apply()

    var bindLan: Boolean
        get() = prefs.getBoolean("bindLan", false)
        set(value) = prefs.edit().putBoolean("bindLan", value).apply()

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    var allowAll: Boolean
        get() = prefs.getBoolean("allowAll", true)
        set(value) = prefs.edit().putBoolean("allowAll", value).apply()

    var allowlist: Set<String>
        get() = prefs.getStringSet("allowlist", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("allowlist", value.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()

    var apiBase: String
        get() = prefs.getString("apiBase", "https://api.openai.com/v1").orEmpty()
        set(value) = prefs.edit().putString("apiBase", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "").orEmpty()
        set(value) = prefs.edit().putString("apiKey", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", "gpt-4o-mini").orEmpty()
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var autoStartServer: Boolean
        get() = prefs.getBoolean("autoStartServer", true)
        set(value) = prefs.edit().putBoolean("autoStartServer", value).apply()

    var agentSteps: Int
        get() = prefs.getInt("agentSteps", 20).coerceIn(1, 50)
        set(value) = prefs.edit().putInt("agentSteps", value.coerceIn(1, 50)).apply()

    fun guardPolicy(): GuardPolicy = GuardPolicy(
        allowAllPackages = allowAll,
        allowlist = allowlist,
        blocklist = emptySet()
    )

    fun statusJson(): JSONObject = JSONObject()
        .put("port", port)
        .put("lan", bindLan)
        .put("auth", token.isNotEmpty())
        .put("model", model)
        .put("aiConfigured", apiKey.isNotEmpty() || apiBase.contains("localhost") || apiBase.contains("127.0.0.1"))
}
