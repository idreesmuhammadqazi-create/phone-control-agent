package dev.pocketpilot.core

import org.json.JSONObject

/**
 * Everything the engine needs from the platform. The Android implementation lives in the app
 * module; unit tests use a scripted fake, which keeps all decision logic testable off-device.
 */
interface Device {
    fun isReady(): Boolean
    fun snapshot(): RawSnapshot?
    fun currentPackage(): String?
    fun waitForIdle(maxMs: Long): Boolean

    fun tap(x: Int, y: Int): Boolean
    fun longTap(x: Int, y: Int, durationMs: Long): Boolean
    fun click(key: String): Boolean
    fun longClick(key: String, durationMs: Long = 700L): Boolean
    fun setText(key: String, text: String, append: Boolean): Boolean
    fun scroll(key: String?, direction: String, amount: Float): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean
    fun press(key: String): Boolean
    fun launch(packageName: String): Boolean
    fun apps(): List<AppInfo>
    fun clipboard(text: String): Boolean
    fun screenshotJpeg(maxWidth: Int): ByteArray?
}

data class EngineConfig(
    val maxNodes: Int = 140,
    val hardMaxNodes: Int = 400,
    val maxTextChars: Int = 100,
    val idleMs: Long = 140,
    /** Upper bound on how long an action waits for the UI to settle. */
    val settleMs: Long = 900,
    val observeWaitMs: Long = 1200,
    /** Refs older than this trigger a fresh capture before acting. */
    val freshRefMs: Long = 3000,
    val maxTaskSteps: Int = 40,
    val maxWaitMs: Long = 30000,
    val diffLines: Int = 60
)

interface EngineApi {
    fun observe(args: JSONObject): JSONObject
    fun execute(tool: String, args: JSONObject, confirmed: Boolean = false): JSONObject
    fun runTask(args: JSONObject): JSONObject
    fun status(): JSONObject
    fun apps(query: String?): JSONObject
    fun confirm(id: String, approve: Boolean): JSONObject
    fun runAgent(goal: String, maxSteps: Int): JSONObject
}
