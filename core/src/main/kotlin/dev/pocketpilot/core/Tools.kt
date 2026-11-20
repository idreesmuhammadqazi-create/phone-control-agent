package dev.pocketpilot.core

import org.json.JSONObject

data class ToolDef(val name: String, val description: String, val schema: String) {
    fun asJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", JSONObject(schema))
}

/**
 * MCP tool catalogue. Schemas and descriptions are part of the prompt budget, so they stay terse
 * and concrete: an agent should be able to use the engine after reading them once.
 */
object Tools {
    const val OBSERVE = "observe"
    const val TAP = "tap"
    const val LONG_PRESS = "long_press"
    const val TYPE = "type"
    const val SCROLL = "scroll"
    const val SWIPE = "swipe"
    const val PRESS = "press"
    const val OPEN_APP = "open_app"
    const val WAIT_FOR = "wait_for"
    const val RUN_TASK = "run_task"
    const val RUN_AGENT = "run_agent"
    const val CONFIRM = "confirm"
    const val APPS = "apps"
    const val STATUS = "status"
    const val SCREENSHOT = "screenshot"

    val defs: List<ToolDef> = listOf(
        ToolDef(
            OBSERVE,
            "Read the screen as compact lines: @ref|parent|role|flags|bounds|id|text|desc|hint. " +
                "Act on @refs. Pass since=<snapshot id> to get only changes.",
            """
            {"type":"object","properties":{
              "since":{"type":["integer","string"],"description":"Snapshot id from a previous observe; returns a diff"},
              "fmt":{"type":"string","enum":["text","json"],"default":"text"},
              "max":{"type":"integer","description":"Max elements, 20-400"},
              "wait":{"type":"integer","description":"Max ms to wait for the UI to settle"}
            },"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            TAP,
            "Tap an element by ref, or by x/y coordinates. Set long=true for a long-press.",
            """
            {"type":"object","properties":{
              "ref":{"type":["integer","string"],"description":"Element ref, e.g. 12 or @12"},
              "x":{"type":"integer"},"y":{"type":"integer"},
              "long":{"type":"boolean"}
            },"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            LONG_PRESS,
            "Long-press an element by ref (or x/y). Often opens context menus; requires approval.",
            """
            {"type":"object","properties":{
              "ref":{"type":["integer","string"]},
              "x":{"type":"integer"},"y":{"type":"integer"}
            },"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            TYPE,
            "Type text into a field (by ref, or the focused field). append=true appends; " +
                "submit=true presses Enter/IME action after typing.",
            """
            {"type":"object","properties":{
              "ref":{"type":["integer","string"]},
              "text":{"type":"string"},
              "append":{"type":"boolean"},
              "submit":{"type":"boolean"}
            },"required":["text"],"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            SCROLL,
            "Scroll a scrollable element or the screen. amount is a fraction of the viewport.",
            """
            {"type":"object","properties":{
              "ref":{"type":["integer","string"]},
              "direction":{"type":"string","enum":["down","up","left","right"],"default":"down"},
              "amount":{"type":"number","minimum":0.1,"maximum":1.0,"default":0.7}
            },"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            SWIPE,
            "Raw swipe between two screen points in pixels.",
            """
            {"type":"object","properties":{
              "x1":{"type":"integer"},"y1":{"type":"integer"},
              "x2":{"type":"integer"},"y2":{"type":"integer"},
              "ms":{"type":"integer","description":"Duration, default 300"}
            },"required":["x1","y1","x2","y2"],"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            PRESS,
            "Press a system key: back, home, recents, notifications, quick_settings, enter.",
            """
            {"type":"object","properties":{
              "key":{"type":"string","enum":["back","home","recents","notifications","quick_settings","enter"]}
            },"required":["key"],"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            OPEN_APP,
            "Launch an installed app by package name or visible name. Resets to the app's main " +
                "screen if its task is already open.",
            """
            {"type":"object","properties":{
              "app":{"type":"string","description":"Package name (com.example.app) or visible name"}
            },"required":["app"],"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            WAIT_FOR,
            "Wait until text appears or a package is in the foreground. Returns the screen on success.",
            """
            {"type":"object","properties":{
              "text":{"type":"string"},
              "package":{"type":"string"},
              "timeout":{"type":"integer","description":"Max ms, default 10000"}
            },"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            RUN_TASK,
            "Run a deterministic multi-step script, e.g. [{tool:\"tap\",args:{ref:12}},{tool:\"wait_for\",args:{text:\"Done\"}}]. " +
                "Use for repeatable workflows; no LLM needed.",
            """
            {"type":"object","properties":{
              "steps":{"type":"array","items":{"type":"object","properties":{
                "tool":{"type":"string"},"args":{"type":"object"},
                "wait":{"type":"integer","description":"ms to pause before the step"},
                "expect":{"type":"string","description":"Text that must appear in the step result"}
              },"required":["tool"]}},
              "stopOnError":{"type":"boolean","default":true}
            },"required":["steps"],"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            RUN_AGENT,
            "Delegate a natural-language goal to the on-device agent. Requires an LLM configured in the app.",
            """
            {"type":"object","properties":{
              "goal":{"type":"string"},"maxSteps":{"type":"integer","default":20}
            },"required":["goal"],"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            CONFIRM,
            "Approve or deny a pending action that was blocked for safety.",
            """
            {"type":"object","properties":{
              "id":{"type":"string"},"approve":{"type":"boolean"}
            },"required":["id","approve"],"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            APPS,
            "List installed launchable apps (package + name), optionally filtered.",
            """
            {"type":"object","properties":{"query":{"type":"string"}},"additionalProperties":false}
            """.trimIndent()
        ),
        ToolDef(
            STATUS,
            "Engine, service and policy status.",
            """{"type":"object","properties":{},"additionalProperties":false}"""
        ),
        ToolDef(
            SCREENSHOT,
            "JPEG screenshot for human debugging. Prefer observe: no vision model is required.",
            """
            {"type":"object","properties":{"maxWidth":{"type":"integer","default":720}},"additionalProperties":false}
            """.trimIndent()
        )
    )

    val byName: Map<String, ToolDef> = defs.associateBy { it.name }
}
