package dev.pocketpilot.app

import dev.pocketpilot.core.PilotEngine
import dev.pocketpilot.core.failure
import dev.pocketpilot.core.ok
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * On-device agent loop. It is deliberately not an autonomous planner with a giant context: each
 * turn sends the goal, a short history and the current compact screen, and asks for exactly one
 * tool call. That keeps latency, cost and memory low enough for a 2 GB phone while leaving the
 * heavy reasoning to any remote OpenAI-compatible model.
 */
class PilotAgent(
    private val app: PilotApplication,
    private val engine: PilotEngine,
    private val settings: Settings
) {

    data class AgentState(
        val running: Boolean = false,
        val goal: String = "",
        val step: Int = 0,
        val maxSteps: Int = 0,
        val status: String = "idle",
        val lastAction: String = "",
        val error: String? = null
    )

    @Volatile
    var state: AgentState = AgentState()
        private set

    @Volatile
    private var stopRequested = false

    private var thread: Thread? = null
    private val startLock = Any()

    fun start(goal: String, maxSteps: Int) {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return
        val steps = maxSteps.coerceIn(1, 50)
        synchronized(startLock) {
            if (state.running) {
                LogBus.add("agent: already running")
                return
            }
            stopRequested = false
            state = AgentState(true, trimmed, 0, steps, "starting", "", null)
            thread = Thread({ run(trimmed, steps) }, "pilot-agent").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun runBlocking(goal: String, maxSteps: Int): JSONObject {
        if (goal.isBlank()) return failure("missing_goal", "goal is required")
        val steps = maxSteps.coerceIn(1, 50)
        synchronized(startLock) {
            if (state.running) return failure("agent_busy", "Agent is already running")
            stopRequested = false
            state = AgentState(true, goal.trim(), 0, steps, "running", "", null)
        }
        return run(goal.trim(), steps)
    }

    fun stop() {
        stopRequested = true
        if (state.running) state = state.copy(status = "stopping")
        LogBus.add("agent: stop requested")
    }

    /** Called by the app after a human resolves a pending approval. */
    fun onConfirmationResolved(approved: Boolean) {
        if (state.status == "awaiting_confirmation") {
            state = state.copy(
                running = false,
                status = if (approved) "confirmed" else "denied"
            )
        }
    }

    /** The pending approval vanished (expired or resolved through MCP); stop claiming we wait. */
    fun clearAwaitingConfirmation() {
        if (state.status == "awaiting_confirmation") {
            state = state.copy(running = false, status = "idle")
        }
    }

    // ------------------------------------------------------------ loop

    private fun run(goal: String, maxSteps: Int): JSONObject {
        LogBus.add("agent start: ${goal.take(90)}")
        val base = settings.apiBase
        val key = settings.apiKey
        val local = base.contains("localhost") || base.contains("127.0.0.1") || base.startsWith("http://")
        if (key.isBlank() && !local) {
            return fail("llm_not_configured", "Add an LLM API key in AI settings")
        }

        val llm = LlmClient(base, key, settings.model)
        val history = ArrayDeque<String>()
        val signatures = ArrayDeque<String>()

        return try {
            val first = engine.observe(JSONObject().put("fmt", "text").put("wait", 1200))
            if (!first.optBoolean("ok", false)) {
                return fail("observe_failed", first.optString("text", "Could not read the screen"))
            }
            var screen = first.optString("text", "")

            for (step in 1..maxSteps) {
                if (stopRequested) return fail("stopped", "Stopped by user")

                val prompt = buildPrompt(goal, step, maxSteps, history, screen)
                val raw = try {
                    llm.chat(prompt)
                } catch (t: Throwable) {
                    val message = t.message ?: t.javaClass.simpleName
                    LogBus.add("agent llm error: $message")
                    return fail("llm_error", message.take(300))
                }

                val action = parseAction(raw)
                if (action == null) {
                    history.addLast("unparsable model output, retry with one JSON object")
                    trim(history, 6)
                    state = state.copy(step = step, status = "retrying")
                    LogBus.add("agent: unparsable step $step")
                    continue
                }

                if (action.optBoolean("done", false)) {
                    val summary = action.optString("summary", "done").take(500)
                    state = state.copy(running = false, status = "done", step = step, lastAction = "done")
                    LogBus.add("agent done at step $step: $summary")
                    return ok()
                        .put("status", "done")
                        .put("summary", summary)
                        .put("steps", step)
                        .put("text", "done in $step steps: $summary")
                }

                val tool = action.optString("tool", "").trim()
                if (tool.isEmpty()) {
                    history.addLast("error: missing tool")
                    trim(history, 6)
                    continue
                }
                val args = action.optJSONObject("args") ?: JSONObject()
                val signature = tool + args.toString()
                if (signatures.size >= 2 && signatures.toList().takeLast(2).all { it == signature }) {
                    return fail("agent_stuck", "Repeated the same action three times; stopping")
                }
                signatures.addLast(signature)
                trim(signatures, 4)

                val result = engine.execute(tool, args)
                val succeeded = result.optBoolean("ok", false)
                val text = result.optString("text", result.toString())
                state = state.copy(
                    step = step,
                    lastAction = "$tool ${if (succeeded) "ok" else "err"}",
                    status = "running",
                    error = if (succeeded) null else result.optString("message").takeIf { it.isNotBlank() }
                )
                LogBus.add("agent $step: $tool ${if (succeeded) "ok" else "err"}")

                if (result.optString("status") == "confirmation_required") {
                    state = state.copy(running = false, status = "awaiting_confirmation")
                    LogBus.add("agent waiting for confirmation ${result.optString("confirmId")}")
                    return result
                }

                history.addLast(
                    "$tool(${args.toString().take(100)}) -> ${if (succeeded) "ok" else "err"}: ${text.take(260)}"
                )
                trim(history, 6)
                screen = if (succeeded) text else "$screen\n\nERROR from last step: ${text.take(400)}"
            }
            fail("max_steps", "Reached $maxSteps steps without finishing")
        } catch (t: Throwable) {
            LogBus.add("agent error: ${t.message}")
            fail("agent_failed", t.message ?: t.javaClass.simpleName)
        } finally {
            if (state.running) state = state.copy(running = false, status = "idle")
        }
    }

    private fun buildPrompt(
        goal: String,
        step: Int,
        maxSteps: Int,
        history: ArrayDeque<String>,
        screen: String
    ): String {
        val recent = if (history.isEmpty()) "(none)" else history.joinToString("\n")
        return """
            You are PocketPilot, an Android automation agent. You control one phone through accessibility data.
            Reply with exactly ONE JSON object, no prose:
            {"tool":"<name>","args":{...}} or {"done":true,"summary":"..."}

            TOOLS
            observe{}
            tap{"ref":N}  long_press{"ref":N}
            type{"ref":N,"text":"...","append":false,"submit":false}
            scroll{"ref":N,"direction":"down|up|left|right","amount":0.7}
            swipe{"x1":0,"y1":0,"x2":0,"y2":0,"ms":300}
            press{"key":"back|home|recents|notifications|quick_settings|enter"}
            open_app{"app":"name or package"}
            wait_for{"text":"...","timeout":10000}
            run_task{"steps":[{"tool":"tap","args":{"ref":5}}]}
            confirm{"id":"c1","approve":true}
            apps{"query":"..."}
            status{}

            SCREEN FORMAT
            S<id> pkg/activity WxH r0 kb0 kept/total
            @ref|parent|role|flags|bounds(l,t,w,h)|id|text|desc|hint
            flags: c clickable, l long-clickable, e editable, s scrollable, f focused, k checked, v selected, x disabled, p password.
            Act only on refs present in the CURRENT screen. Refs are short-lived; never guess one.

            RULES
            1. One action per turn, then read the returned screen or diff.
            2. Never repeat an identical action more than twice; observe again instead.
            3. Destructive actions return confirmation_required. Approve only if the goal clearly authorizes it; otherwise stop and explain.
            4. Stop as soon as the goal is satisfied.

            GOAL: $goal
            STEP: $step/$maxSteps
            RECENT:
            $recent

            CURRENT SCREEN:
            ${screen.take(7000)}
        """.trimIndent()
    }

    private fun parseAction(raw: String): JSONObject? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.dropLast(3).trim()
        }
        direct(text)?.let { return it }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return direct(text.substring(start, end + 1))
        }
        return null
    }

    private fun direct(candidate: String): JSONObject? = try {
        val obj = JSONObject(candidate)
        if (obj.has("tool") || obj.has("done")) obj else null
    } catch (_: Throwable) {
        null
    }

    private fun trim(queue: ArrayDeque<String>, max: Int) {
        while (queue.size > max) queue.removeFirst()
    }

    private fun fail(code: String, message: String): JSONObject {
        state = state.copy(running = false, status = "error", error = message.take(200))
        LogBus.add("agent $code: ${message.take(120)}")
        return failure(code, message)
    }
}
