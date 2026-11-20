package dev.pocketpilot.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * The automation engine: observation, element-addressed actions, waits, declarative task runs
 * and the human confirmation gate. It is platform-agnostic and fully unit-testable.
 */
class PilotEngine(
    private val device: Device,
    val guard: Guard = Guard(),
    private val config: EngineConfig = EngineConfig()
) : EngineApi {

    private val refs = RefRegistry()
    private val compactor = Compactor(
        CompactConfig(
            maxNodes = config.maxNodes,
            hardMaxNodes = config.hardMaxNodes,
            maxTextChars = config.maxTextChars
        )
    )
    private val lock = ReentrantLock()
    private val renders = object : LinkedHashMap<Long, Render>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Render>?): Boolean = size > 8
    }

    private var nextId = 0L

    @Volatile
    private var latest: Render? = null
    private val confirmations = ConfirmationStore()

    /** Called when an action needs a human decision; the app posts a notification. */
    var onConfirmation: ((Pending) -> Unit)? = null

    /** Lets the app add server/agent fields to status. */
    var statusExtra: (() -> JSONObject)? = null

    /** Set by the app to the on-device LLM agent loop. */
    var agentRunner: ((String, Int) -> JSONObject)? = null

    var logger: ((String) -> Unit)? = null

    // ---------------------------------------------------------------- observe

    override fun observe(args: JSONObject): JSONObject = lock.withLock {
        try {
            doObserve(args)
        } catch (t: Throwable) {
            failure("observe_failed", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun doObserve(args: JSONObject): JSONObject {
        if (!device.isReady()) return serviceOff()
        val waitArg = args.opt("wait")
        val waitMs: Long = when (waitArg) {
            is Number -> waitArg.toLong()
            is String -> waitArg.toLongOrNull() ?: config.observeWaitMs
            else -> config.observeWaitMs
        }
        if (waitMs > 0) device.waitForIdle(waitMs.coerceIn(0L, 5000L))

        val snapshot = device.snapshot() ?: return noWindow()
        val fmt = args.optString("fmt", "text").lowercase()
        val withJson = fmt == "json"
        val max = args.optInt("max", 0).takeIf { it in 1..config.hardMaxNodes }
        val render = renderSnapshot(snapshot, max, withJson)

        val sinceId = parseSince(args.opt("since"))
        val prev = if (sinceId > 0 && sinceId != render.id) renders[sinceId] else null

        val out = ok()
        out.put("snapshot", render.id)
        out.put("pkg", snapshot.packageName)
        snapshot.activity?.takeIf { it.isNotBlank() }?.let { out.put("act", it) }
        out.put("screen", JSONArray().put(snapshot.screen.width).put(snapshot.screen.height))
        out.put("elements", render.lines.size)
        out.put("total", render.totalNodes)
        out.put("tokens", render.tokens)
        out.put("truncated", render.truncated)
        out.put("format", if (withJson) "json" else "text")

        if (withJson) {
            val arr = JSONArray()
            for (line in render.lines) arr.put(line.toJson())
            out.put("elementsJson", arr)
            out.put("tree", render.text)
            out.put("text", render.text)
        } else {
            out.put("text", render.text)
        }

        if (prev != null) {
            val diff = Differ.diff(prev, render, config.diffLines)
            if (diff != null) {
                out.put("diff", true)
                out.put(
                    "changes",
                    JSONObject()
                        .put("added", diff.added)
                        .put("removed", diff.removed)
                        .put("changed", diff.changed)
                )
                out.put("text", "${render.header()}\n${diff.text}")
            } else {
                out.put("changes", JSONObject().put("full", true))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- execute

    override fun execute(tool: String, args: JSONObject, confirmed: Boolean): JSONObject {
        // The agent can take minutes; never hold the engine lock for it.
        if (tool == "run_agent") {
            return try {
                runAgent(args.optString("goal", ""), args.optInt("maxSteps", 20))
            } catch (t: Throwable) {
                failure("agent_failed", t.message ?: t.javaClass.simpleName)
            }
        }
        return lock.withLock {
            try {
                dispatch(tool, args, confirmed)
            } catch (t: Throwable) {
                failure("action_failed", t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun dispatch(tool: String, args: JSONObject, confirmed: Boolean): JSONObject {
        if (!device.isReady()) return serviceOff()
        return when (tool) {
            "tap" -> tap(args, confirmed, long = false)
            "long_press" -> tap(args, confirmed, long = true)
            "type" -> type(args, confirmed)
            "scroll" -> scroll(args, confirmed)
            "swipe" -> swipe(args, confirmed)
            "press" -> press(args, confirmed)
            "open_app" -> openApp(args)
            "wait_for" -> waitFor(args)
            "screenshot" -> screenshot(args)
            "observe" -> doObserve(args)
            "status" -> status()
            "apps" -> apps(args.optString("query", "").ifBlank { null })
            "confirm" -> confirm(args.optString("id", ""), args.optBoolean("approve", false))
            "run_task" -> runTask(args)
            "run_agent" -> runAgent(args.optString("goal", ""), args.optInt("maxSteps", 20))
            else -> failure("unknown_tool", "Unknown tool: $tool")
        }
    }

    // ---------------------------------------------------------------- actions

    private fun tap(args: JSONObject, confirmed: Boolean, long: Boolean): JSONObject {
        val x = args.opt("x")
        val y = args.opt("y")
        if (x is Number && y is Number) {
            val render = freshRender() ?: return noWindow()
            gate("tap", args, null, false, render.snapshot.packageName, confirmed)?.let { return it }
            val ok = if (long) {
                device.longTap(x.toInt(), y.toInt(), 700L)
            } else {
                device.tap(x.toInt(), y.toInt())
            }
            if (!ok) return failure("action_failed", "Gesture was rejected at ${x.toInt()},${y.toInt()}")
            return finish(if (long) "long_press" else "tap", null, render)
        }

        val target = resolveTarget(args) ?: return noWindow()
        target.error?.let { return it }
        val line = target.line ?: return failure("missing_ref", "Provide a ref, or x and y")
        val pre = latest
        gate(if (long) "long_press" else "tap", args, labelOf(line), line.flags.contains('p'), pre?.snapshot?.packageName, confirmed)?.let { return it }

        val ok = if (long) device.longClick(line.key, 700L) else device.click(line.key)
        if (!ok) {
            return failure(
                "action_failed",
                "Could not activate @${line.ref}",
                "The element may have moved or is not clickable. Call observe and retry."
            )
        }
        return finish(if (long) "long_press" else "tap", line, pre)
    }

    private fun type(args: JSONObject, confirmed: Boolean): JSONObject {
        if (!args.has("text")) return failure("missing_text", "text is required (use an empty string to clear)")
        val target = resolveTarget(args, needEditable = true) ?: return noWindow()
        target.error?.let { return it }
        val line = target.line ?: return failure("no_focused_input", "Tap a text field first or pass ref")
        val pre = latest
        gate("type", args, labelOf(line), line.flags.contains('p'), pre?.snapshot?.packageName, confirmed)?.let { return it }

        val text = args.optString("text", "")
        val append = args.optBoolean("append", false)
        if (!device.setText(line.key, text, append)) {
            return failure("action_failed", "Could not enter text into @${line.ref}")
        }
        if (args.optBoolean("submit", false)) device.press("enter")
        return finish("type", line, pre)
    }

    private fun scroll(args: JSONObject, confirmed: Boolean): JSONObject {
        val direction = args.optString("direction", "down").lowercase()
        if (direction !in setOf("down", "up", "left", "right")) {
            return failure("bad_direction", "direction must be down, up, left or right")
        }
        val amount = args.optDouble("amount", 0.7).toFloat().coerceIn(0.1f, 1.0f)
        val target = resolveTarget(args)
        val line = target?.line
        if (target?.error != null) return target.error
        val pre = freshRender()
        gate("scroll", args, line?.let { labelOf(it) }, false, pre?.snapshot?.packageName, confirmed)?.let { return it }
        if (!device.scroll(line?.key, direction, amount)) {
            return failure("action_failed", "Could not scroll $direction", "Call observe to check the current screen.")
        }
        return finish("scroll", line, pre)
    }

    private fun swipe(args: JSONObject, confirmed: Boolean): JSONObject {
        val x1 = args.optInt("x1", Int.MIN_VALUE)
        val y1 = args.optInt("y1", Int.MIN_VALUE)
        val x2 = args.optInt("x2", Int.MIN_VALUE)
        val y2 = args.optInt("y2", Int.MIN_VALUE)
        if (x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE || x2 == Int.MIN_VALUE || y2 == Int.MIN_VALUE) {
            return failure("missing_coords", "x1, y1, x2 and y2 are required")
        }
        val duration = args.optInt("ms", 300).coerceIn(50, 3000)
        val pre = freshRender() ?: return noWindow()
        gate("swipe", args, null, false, pre.snapshot.packageName, confirmed)?.let { return it }
        if (!device.swipe(x1, y1, x2, y2, duration.toLong())) {
            return failure("action_failed", "Gesture was rejected")
        }
        return finish("swipe", null, pre)
    }

    private fun press(args: JSONObject, confirmed: Boolean): JSONObject {
        val key = args.optString("key", "").lowercase()
        if (key !in setOf("back", "home", "recents", "notifications", "quick_settings", "enter")) {
            return failure("bad_key", "key must be back, home, recents, notifications, quick_settings or enter")
        }
        val pre = freshRender() ?: return noWindow()
        gate("press", args, null, false, pre.snapshot.packageName, confirmed)?.let { return it }
        if (!device.press(key)) return failure("action_failed", "Could not press $key")
        return finish("press", null, pre, JSONObject().put("key", key))
    }

    private fun openApp(args: JSONObject): JSONObject {
        val query = args.optString("app", "").trim()
        if (query.isEmpty()) return failure("missing_app", "app is required (package name or visible label)")
        val packageName: String
        if (query.contains('.')) {
            packageName = query
        } else {
            val all = try {
                device.apps()
            } catch (t: Throwable) {
                return failure("app_list_failed", t.message ?: "Could not list installed apps")
            }
            val exact = all.filter { it.label.equals(query, ignoreCase = true) }
            val matches = if (exact.isNotEmpty()) exact else all.filter { it.label.contains(query, ignoreCase = true) }
            if (matches.isEmpty()) {
                return failure("app_not_found", "No installed app matches \"$query\"", "Use apps to search by name.")
            }
            if (matches.size > 1) {
                return failure(
                    "ambiguous_app",
                    "\"$query\" matches ${matches.size} apps",
                    matches.take(6).joinToString(" | ") { "${it.packageName} (${it.label})" }
                )
            }
            packageName = matches[0].packageName
        }
        when (val v = guard.checkPackage(packageName)) {
            is Verdict.Deny -> return failure(v.code, v.reason)
            else -> Unit
        }
        val pre = latest
        if (!device.launch(packageName)) {
            return failure("launch_failed", "Could not launch $packageName")
        }
        return finish("open_app", null, pre, JSONObject().put("package", packageName))
    }

    private fun waitFor(args: JSONObject): JSONObject {
        val text = args.optString("text", "").trim()
        val pkg = args.optString("package", "").trim()
        if (text.isEmpty() && pkg.isEmpty()) {
            return failure("missing_condition", "text or package is required")
        }
        val timeout = args.optInt("timeout", 10000).coerceIn(200, config.maxWaitMs.toInt())
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started <= timeout) {
            val snapshot = device.snapshot()
            if (snapshot != null) {
                val pkgOk = pkg.isEmpty() || snapshot.packageName == pkg
                val textOk = text.isEmpty() || snapshot.nodes.any { it.label.contains(text, ignoreCase = true) }
                if (pkgOk && textOk) {
                    val render = renderSnapshot(snapshot, null, false)
                    val waited = System.currentTimeMillis() - started
                    val out = ok()
                        .put("waitedMs", waited)
                        .put("snapshot", render.id)
                        .put("text", "ok waited ${waited}ms\n${render.text}")
                    return out
                }
            }
            Thread.sleep(200)
        }
        return failure(
            "timeout",
            "Condition not met within ${timeout}ms",
            "Call observe to inspect the current screen."
        )
    }

    private fun screenshot(args: JSONObject): JSONObject {
        val maxWidth = args.optInt("maxWidth", 720).coerceIn(320, 1440)
        val bytes = device.screenshotJpeg(maxWidth)
            ?: return failure("unsupported", "Screenshots require Android 11+ and the accessibility screenshot capability")
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return ok()
            .put("image", encoded)
            .put("mimeType", "image/jpeg")
            .put("bytes", bytes.size)
            .put("text", "ok screenshot ${bytes.size}B")
    }

    // ---------------------------------------------------------------- tasks

    override fun runTask(args: JSONObject): JSONObject {
        val steps = args.optJSONArray("steps") ?: return failure("missing_steps", "steps[] is required")
        val stopOnError = args.optBoolean("stopOnError", true)
        val limit = min(steps.length(), config.maxTaskSteps)
        val results = JSONArray()
        val summary = StringBuilder()
        var failed = false

        for (i in 0 until limit) {
            val step = steps.optJSONObject(i) ?: continue
            val tool = step.optString("tool", "")
            if (tool.isEmpty()) {
                failed = true
                results.put(JSONObject().put("step", i + 1).put("ok", false).put("error", "missing_tool"))
                summary.append(i + 1).append(" FAIL missing_tool\n")
                break
            }
            val stepArgs = step.optJSONObject("args") ?: JSONObject()
            val wait = step.optInt("wait", 0)
            if (wait > 0) Thread.sleep(wait.coerceAtMost(5000).toLong())

            val result = execute(tool, stepArgs)
            val text = result.optString("text", "")
            val expect = step.optString("expect", "")
            val expectOk = expect.isEmpty() || text.contains(expect, true)
            val okStep = result.optBoolean("ok", false) && expectOk

            val record = JSONObject()
                .put("step", i + 1)
                .put("tool", tool)
                .put("ok", okStep)
                .put("text", text.take(400))
            if (!expectOk) record.put("error", "expect_failed")
            else if (!result.optBoolean("ok", false)) record.put("error", result.optString("error", "failed"))
            results.put(record)

            summary.append(i + 1).append(' ').append(tool).append(if (okStep) " ok" else " FAIL").append('\n')
            if (!okStep) {
                failed = true
                if (stopOnError) break
            }
        }

        val out = JSONObject()
            .put("ok", !failed)
            .put("failed", failed)
            .put("steps", results)
            .put("text", summary.toString().trimEnd())
        if (failed) out.put("error", "task_failed")
        return out
    }

    override fun runAgent(goal: String, maxSteps: Int): JSONObject {
        if (goal.isBlank()) return failure("missing_goal", "goal is required")
        val runner = agentRunner
            ?: return failure("agent_unavailable", "On-device agent is not configured")
        return try {
            runner(goal, maxSteps.coerceIn(1, 50))
        } catch (t: Throwable) {
            failure("agent_failed", t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- confirm

    override fun confirm(id: String, approve: Boolean): JSONObject = lock.withLock {
        val pending = confirmations.take(id)
            ?: return@withLock failure("unknown_confirmation", "No pending confirmation with id \"$id\"")
        if (!approve) {
            return@withLock ok().put("status", "denied").put("text", "denied $id")
        }
        dispatch(pending.tool, pending.args, true)
    }

    /** Pending human approvals; surfaced in the app UI as well as notifications. */
    fun pendingConfirmations(): List<Pending> = confirmations.peek()

    // ---------------------------------------------------------------- status

    override fun status(): JSONObject {
        val out = ok()
        out.put("service", device.isReady())
        val snapshot = latest?.snapshot
        out.put("pkg", device.currentPackage() ?: snapshot?.packageName ?: "")
        latest?.let {
            out.put("snapshot", it.id)
            out.put("elements", it.lines.size)
            out.put("tokens", it.tokens)
        }
        snapshot?.let { out.put("screen", JSONArray().put(it.screen.width).put(it.screen.height)) }
        out.put("allowAll", guard.policy.allowAllPackages)
        out.put("allowlist", guard.policy.allowlist.size)
        out.put("pendingConfirms", confirmations.peek().size)
        out.put("refs", refs.refCount())
        statusExtra?.invoke()?.let { extra ->
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out.put(k, extra.get(k))
            }
        }
        val sb = StringBuilder("ok")
        sb.append(" service=").append(if (device.isReady()) 1 else 0)
        sb.append(" pkg=").append(out.optString("pkg", "?"))
        latest?.let { sb.append(" snap=").append(it.id).append(" els=").append(it.lines.size) }
        sb.append(" allowAll=").append(if (guard.policy.allowAllPackages) 1 else 0)
        out.put("text", sb.toString())
        return out
    }

    override fun apps(query: String?): JSONObject {
        val all = try {
            device.apps()
        } catch (t: Throwable) {
            return failure("app_list_failed", t.message ?: "Could not list apps")
        }
        val q = query?.trim()?.lowercase()
        val matches = if (q.isNullOrEmpty()) {
            all
        } else {
            all.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        val capped = matches.take(40)
        val arr = JSONArray()
        val text = StringBuilder()
        for (a in capped) {
            arr.put(JSONObject().put("pkg", a.packageName).put("name", a.label))
            text.append(a.packageName).append(' ').append(a.label).append('\n')
        }
        return ok()
            .put("apps", arr)
            .put("count", matches.size)
            .put("text", if (capped.isEmpty()) "no apps" else text.toString().trimEnd())
    }

    // ---------------------------------------------------------------- internals

    private fun renderSnapshot(snapshot: RawSnapshot, max: Int?, withJson: Boolean): Render {
        val redacted = guard.redactSnapshot(snapshot)
        refs.begin()
        val id = nextId + 1
        nextId = id
        val render = compactor.compile(redacted, refs, id, max, withJson)
        refs.end()
        renders[id] = render
        latest = render
        return render
    }

    private fun freshRender(): Render? {
        val current = latest
        val now = System.currentTimeMillis()
        if (current != null && now - current.snapshot.timestamp <= config.freshRefMs) return current
        val snapshot = device.snapshot() ?: return current
        return renderSnapshot(snapshot, null, false)
    }

    private class Target(val line: Line?, val error: JSONObject?)

    private fun resolveTarget(args: JSONObject, needEditable: Boolean = false): Target? {
        val render = freshRender() ?: return null
        val raw = args.opt("ref")
        if (raw == null) {
            if (needEditable) {
                val focused = render.lines.firstOrNull { it.role == Roles.INPUT && it.flags.contains('f') }
                if (focused != null) return Target(focused, null)
                return Target(
                    null,
                    failure(
                        "no_focused_input",
                        "No text field is focused",
                        "Call observe and tap an input first, or pass ref."
                    )
                )
            }
            return Target(null, null)
        }
        val ref = RefRegistry.parse(raw)
            ?: return Target(null, failure("bad_ref", "Invalid ref: $raw"))
        val line = render.byRef[ref]
            ?: return Target(
                null,
                failure(
                    "stale_ref",
                    "Ref @$ref is not on the current screen",
                    "Call observe again; refs are only valid while the element exists."
                )
            )
        if (needEditable && line.role != Roles.INPUT) {
            return Target(
                null,
                failure("not_editable", "@$ref is not a text field", "Tap the field first or pass the correct ref.")
            )
        }
        return Target(line, null)
    }

    private fun gate(
        tool: String,
        args: JSONObject,
        label: String?,
        password: Boolean,
        pkg: String?,
        confirmed: Boolean
    ): JSONObject? {
        if (confirmed) return null
        return when (val verdict = guard.checkAction(tool, args, label, password, pkg)) {
            is Verdict.Allow -> null
            is Verdict.Deny -> failure(verdict.code, verdict.reason)
            is Verdict.Confirm -> {
                val pending = confirmations.create(tool, args, verdict.reason, verdict.detail, pkg)
                onConfirmation?.invoke(pending)
                confirmationRequired(pending, verdict)
            }
        }
    }

    private fun confirmationRequired(pending: Pending, verdict: Verdict.Confirm): JSONObject {
        val out = JSONObject()
            .put("ok", false)
            .put("status", "confirmation_required")
            .put("error", "confirmation_required")
            .put("confirmId", pending.id)
            .put("reason", verdict.reason)
            .put(
                "hint",
                "Call confirm with {id:\"${pending.id}\",approve:true} to proceed, or ask the user."
            )
        verdict.detail?.let { out.put("detail", it) }
        val detail = verdict.detail?.let { " ($it)" } ?: ""
        out.put("text", "confirm ${pending.id}: ${verdict.reason}$detail")
        return out
    }

    private fun finish(action: String, line: Line?, pre: Render?, extra: JSONObject? = null): JSONObject {
        device.waitForIdle(config.settleMs)
        val snapshot = device.snapshot()
        val prefix = "ok $action" + (line?.let { " @${it.ref}" } ?: "")
        if (snapshot == null) {
            return ok().put("action", action).put("snapshot", 0).put("text", "$prefix (window gone)")
        }
        val post = renderSnapshot(snapshot, null, false)
        val out = ok().put("action", action).put("snapshot", post.id)
        line?.let { out.put("ref", it.ref) }
        extra?.let { out.put("result", it) }

        val diff = Differ.diff(pre, post, config.diffLines)
        if (diff == null) {
            out.put("text", "$prefix\n${post.text}")
        } else {
            out.put(
                "changes",
                JSONObject()
                    .put("added", diff.added)
                    .put("removed", diff.removed)
                    .put("changed", diff.changed)
            )
            out.put(
                "text",
                if (diff.added + diff.removed + diff.changed == 0) {
                    "$prefix (no change)"
                } else {
                    "$prefix\n${post.header()}\n${diff.text}"
                }
            )
        }
        return out
    }

    private fun labelOf(line: Line): String =
        line.text?.takeIf { it.isNotBlank() }
            ?: line.desc?.takeIf { it.isNotBlank() }
            ?: line.hint?.takeIf { it.isNotBlank() }
            ?: ""

    private fun parseSince(raw: Any?): Long = when (raw) {
        is Number -> raw.toLong()
        is String -> raw.filter { it.isDigit() }.toLongOrNull() ?: -1L
        else -> -1L
    }

    private fun serviceOff(): JSONObject = failure(
        "service_off",
        "The accessibility service is not running",
        "Open PocketPilot and enable the accessibility service."
    )

    private fun noWindow(): JSONObject = failure(
        "no_window",
        "No accessible window right now",
        "Unlock the phone and open an app, or call observe again."
    )

    private fun log(message: String) {
        logger?.invoke(message)
    }
}
