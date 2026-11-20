package dev.pocketpilot.core

import org.json.JSONObject

class FakeDevice(
    var screen: RawSnapshot,
    var apps: List<AppInfo> = listOf(AppInfo("com.example.app", "Example")),
    var ready: Boolean = true
) : Device {
    val clicked = mutableListOf<String>()
    val gestures = mutableListOf<String>()
    var typed: Pair<String, String>? = null
    var launched: String? = null
    var pressed: String? = null
    var scrollDirection: String? = null

    override fun isReady(): Boolean = ready
    override fun snapshot(): RawSnapshot = screen
    override fun currentPackage(): String = screen.packageName
    override fun waitForIdle(maxMs: Long): Boolean = true

    override fun tap(x: Int, y: Int): Boolean {
        gestures.add("tap $x,$y")
        return true
    }

    override fun longTap(x: Int, y: Int, durationMs: Long): Boolean {
        gestures.add("longTap $x,$y")
        return true
    }

    override fun click(key: String): Boolean {
        clicked.add(key)
        return true
    }

    override fun longClick(key: String, durationMs: Long): Boolean {
        clicked.add("long:$key")
        return true
    }

    override fun setText(key: String, text: String, append: Boolean): Boolean {
        typed = key to text
        return true
    }

    override fun scroll(key: String?, direction: String, amount: Float): Boolean {
        scrollDirection = direction
        gestures.add("scroll $direction")
        return true
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        gestures.add("swipe")
        return true
    }

    override fun press(key: String): Boolean {
        pressed = key
        return true
    }

    override fun launch(packageName: String): Boolean {
        launched = packageName
        return true
    }

    override fun apps(): List<AppInfo> = apps
    override fun clipboard(text: String): Boolean = true
    override fun screenshotJpeg(maxWidth: Int): ByteArray? = null
}

object Fixtures {
    const val PKG = "com.example.app"

    fun screen(
        vararg nodes: RawNode,
        pkg: String = PKG,
        activity: String = ".Main",
        keyboard: Boolean = false
    ): RawSnapshot = RawSnapshot(
        packageName = pkg,
        activity = activity,
        screen = Bounds(0, 0, 1080, 2400),
        keyboardVisible = keyboard,
        nodes = nodes.toList(),
        timestamp = System.currentTimeMillis()
    )

    fun node(
        key: String,
        parent: Int = -1,
        role: String = Roles.TEXT,
        text: String? = null,
        desc: String? = null,
        hint: String? = null,
        id: String? = null,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        focused: Boolean = false,
        checked: Boolean = false,
        password: Boolean = false,
        bounds: Bounds = Bounds(0, 0, 200, 60),
        childCount: Int = 0,
        visible: Boolean = true,
        depth: Int = 0
    ): RawNode = RawNode(
        key = key,
        parent = parent,
        role = role,
        text = text,
        desc = desc,
        hint = hint,
        viewId = id,
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        editable = editable,
        scrollable = scrollable,
        focused = focused,
        checked = checked,
        password = password,
        visible = visible,
        childCount = childCount,
        depth = depth
    )

    fun settingsScreen(): RawSnapshot = screen(
        node("r", -1, Roles.CONTAINER, childCount = 3),
        node("r/0", 0, Roles.TEXT, text = "Settings", id = "title"),
        node("r/1", 0, Roles.CONTAINER, id = "card", childCount = 2),
        node("r/1/0", 2, Roles.TEXT, text = "Wi-Fi", id = "title"),
        node("r/1/1", 2, Roles.SWITCH, text = "Connected", clickable = true, checked = true),
        node("r/2", 0, Roles.IMAGE, bounds = Bounds(0, 0, 20, 20))
    )

    fun refFor(render: Render, label: String): Int =
        render.lines.first { (it.text ?: it.desc ?: it.hint ?: "") == label }.ref

    fun refForText(text: String, label: String): Int =
        text.lineSequence()
            .filter { it.startsWith("@") }
            .map { it.split('|') }
            .first { fields ->
                fields.size >= 7 && (fields[6] == label || (fields.size > 7 && fields[7] == label) ||
                    (fields.size > 8 && fields[8] == label))
            }[0]
            .removePrefix("@")
            .toInt()

    fun responseText(result: JSONObject): String = result.optString("text", result.toString())
}

class FakeEngine : EngineApi {
    val calls = mutableListOf<String>()
    var toolResponse: JSONObject = ok().put("text", "ok tool")
    var statusResponse: JSONObject = ok().put("service", true).put("text", "ok status")
    var observeResponse: JSONObject = ok().put("text", "S1 com.example.app/.Main 1080x2400 r0 kb0 1/1").put("snapshot", 1)

    override fun observe(args: JSONObject): JSONObject = observeResponse
    override fun execute(tool: String, args: JSONObject, confirmed: Boolean): JSONObject {
        calls.add(tool)
        return toolResponse
    }

    override fun runTask(args: JSONObject): JSONObject = ok().put("text", "task")
    override fun status(): JSONObject = statusResponse
    override fun apps(query: String?): JSONObject = ok().put("text", "apps")
    override fun confirm(id: String, approve: Boolean): JSONObject = ok().put("text", "confirm")
    override fun runAgent(goal: String, maxSteps: Int): JSONObject = ok().put("text", "agent")
}
