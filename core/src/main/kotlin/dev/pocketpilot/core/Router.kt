package dev.pocketpilot.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

data class HttpResult(val status: Int, val contentType: String, val body: String)

/**
 * Transport-neutral request handler: JSON-RPC 2.0 for MCP plus a small REST surface for curl,
 * scripts and non-MCP agents.
 *
 * The MCP endpoint is Streamable HTTP: POST JSON-RPC to /mcp, JSON responses back, no session and
 * no SSE stream. That is enough for tools-only servers and keeps memory flat on low-end phones.
 */
class Router(
    private val engine: EngineApi,
    private val serverName: String = "pocketpilot",
    private val serverVersion: String = "0.1.0",
    private val token: String? = null
) {
    private val supportedVersions = setOf("2024-11-05", "2025-03-26", "2025-06-18")
    private val toolsJson: JSONArray = JSONArray().apply {
        for (def in Tools.defs) put(def.asJson())
    }

    // ------------------------------------------------------------ JSON-RPC

    fun handleRpc(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return rpcError(null, -32700, "Empty request").toString()
        return try {
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    val response = if (item == null) {
                        rpcError(null, -32600, "Invalid request")
                    } else {
                        handleOne(item)
                    }
                    if (response != null) out.put(response)
                }
                if (out.length() == 0) null else out.toString()
            } else {
                handleOne(JSONObject(text))?.toString()
            }
        } catch (t: Throwable) {
            rpcError(null, -32700, "Parse error").toString()
        }
    }

    private fun handleOne(req: JSONObject): JSONObject? {
        val id = if (req.has("id")) req.opt("id") else null
        val method = req.optString("method", "")
        if (method.isEmpty()) return rpcError(id, -32600, "Invalid request")
        if (!req.has("id")) return null // notification
        return try {
            when (method) {
                "initialize" -> rpcResult(id, initialize(req.optJSONObject("params")))
                "ping" -> rpcResult(id, JSONObject())
                "tools/list" -> rpcResult(id, JSONObject().put("tools", toolsJson))
                "tools/call" -> rpcResult(id, callTool(req.optJSONObject("params")))
                "resources/list" -> rpcResult(id, JSONObject().put("resources", JSONArray()))
                "prompts/list" -> rpcResult(id, JSONObject().put("prompts", JSONArray()))
                else -> rpcError(id, -32601, "Method not found: $method")
            }
        } catch (t: Throwable) {
            rpcError(id, -32603, t.message ?: "Internal error")
        }
    }

    private fun initialize(params: JSONObject?): JSONObject {
        val requested = params?.optString("protocolVersion", "").orEmpty()
        val version = if (requested in supportedVersions) requested else "2025-06-18"
        return JSONObject()
            .put("protocolVersion", version)
            .put(
                "capabilities",
                JSONObject().put("tools", JSONObject().put("listChanged", false))
            )
            .put("serverInfo", JSONObject().put("name", serverName).put("version", serverVersion))
            .put(
                "instructions",
                "Control the phone by element refs. observe -> act on @refs; action results include a " +
                    "diff of the new screen. Destructive actions return confirmation_required; approve " +
                    "with confirm if the goal clearly authorizes it. No screenshots or vision needed."
            )
    }

    private fun callTool(params: JSONObject?): JSONObject {
        val name = params?.optString("name", "")?.trim().orEmpty()
        if (name.isEmpty()) return toolError("Missing tool name")
        if (!Tools.byName.containsKey(name)) return toolError("Unknown tool: $name")
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        val result = try {
            engine.execute(name, args)
        } catch (t: Throwable) {
            failure("tool_failed", t.message ?: t.javaClass.simpleName)
        }
        return toToolResult(result)
    }

    private fun toToolResult(result: JSONObject): JSONObject {
        val content = JSONArray()
        if (result.has("image")) {
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put("data", result.optString("image"))
                    .put("mimeType", result.optString("mimeType", "image/jpeg"))
            )
        } else {
            val text = result.optString("text", "")
            content.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", if (text.isNotEmpty()) text else result.toString())
            )
        }
        val out = JSONObject()
            .put("content", content)
            .put("isError", !result.optBoolean("ok", true))
        if (!result.has("image") && result.toString().length <= 6000) {
            out.put("structuredContent", result)
        }
        return out
    }

    private fun toolError(message: String): JSONObject {
        val out = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "err $message")))
            .put("isError", true)
        return out
    }

    private fun rpcResult(id: Any?, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    // ------------------------------------------------------------ HTTP

    fun handleHttp(
        method: String,
        path: String,
        query: String?,
        body: String,
        authorization: String?
    ): HttpResult {
        val m = method.uppercase()
        if (m == "OPTIONS") return HttpResult(204, "text/plain", "")
        val cleanPath = path.trimEnd('/').ifEmpty { "/" }

        if (cleanPath == "/health") {
            return json(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("name", serverName)
                    .put("version", serverVersion)
                    .put("tools", Tools.defs.size)
            )
        }
        if (token != null && !authorized(authorization)) {
            return json(401, failure("unauthorized", "Missing or invalid bearer token"))
        }

        return try {
            when {
                cleanPath == "/mcp" && m == "GET" -> HttpResult(
                    405,
                    "application/json",
                    failure("method_not_allowed", "Use POST for MCP; this server does not stream").toString()
                )
                cleanPath == "/mcp" && m == "POST" -> {
                    val response = handleRpc(body)
                    if (response == null) HttpResult(202, "application/json", "")
                    else HttpResult(200, "application/json", response)
                }
                cleanPath == "/" && m == "POST" -> {
                    val response = handleRpc(body)
                    if (response == null) HttpResult(202, "application/json", "")
                    else HttpResult(200, "application/json", response)
                }
                cleanPath == "/" && m == "GET" -> HttpResult(200, "text/html; charset=utf-8", htmlStatus())
                cleanPath == "/v1/observe" -> {
                    val args = if (m == "POST" && body.isNotBlank()) JSONObject(body) else argsFromQuery(query)
                    json(200, engine.observe(args))
                }
                cleanPath == "/v1/status" && m == "GET" -> json(200, engine.status())
                cleanPath == "/v1/apps" && m == "GET" -> {
                    val q = queryParam(query, "q")
                    json(200, engine.apps(q?.takeIf { it.isNotBlank() }))
                }
                cleanPath == "/v1/task" && m == "POST" -> {
                    if (body.isBlank()) json(400, failure("empty_body", "JSON body required"))
                    else json(200, engine.runTask(JSONObject(body)))
                }
                cleanPath.startsWith("/v1/tool/") -> {
                    if (m != "POST") {
                        json(405, failure("method_not_allowed", "Use POST"))
                    } else {
                        val name = cleanPath.removePrefix("/v1/tool/")
                        if (!Tools.byName.containsKey(name)) {
                            json(404, failure("unknown_tool", "Unknown tool: $name"))
                        } else {
                            val args = if (body.isBlank()) JSONObject() else JSONObject(body)
                            json(200, engine.execute(name, args))
                        }
                    }
                }
                else -> json(404, failure("not_found", "No route for ${m} $cleanPath"))
            }
        } catch (t: Throwable) {
            json(400, failure("bad_request", t.message ?: "Invalid request"))
        }
    }

    private fun authorized(header: String?): Boolean {
        val expected = token ?: return true
        if (header == null) return false
        val prefix = "Bearer "
        if (!header.startsWith(prefix, ignoreCase = true)) return false
        return header.substring(prefix.length).trim() == expected
    }

    private fun json(status: Int, obj: JSONObject): HttpResult =
        HttpResult(status, "application/json", obj.toString())

    private fun argsFromQuery(query: String?): JSONObject {
        val out = JSONObject()
        queryParam(query, "since")?.let { out.put("since", it) }
        queryParam(query, "fmt")?.let { out.put("fmt", it) }
        queryParam(query, "max")?.toIntOrNull()?.let { out.put("max", it) }
        queryParam(query, "wait")?.toIntOrNull()?.let { out.put("wait", it) }
        return out
    }

    private fun queryParam(query: String?, key: String): String? {
        if (query.isNullOrEmpty()) return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            if (rawKey != key) continue
            val rawValue = if (idx >= 0) pair.substring(idx + 1) else ""
            return try {
                URLDecoder.decode(rawValue, "UTF-8")
            } catch (t: Throwable) {
                rawValue
            }
        }
        return null
    }

    private fun htmlStatus(): String {
        val status = engine.status()
        val pkg = escapeHtml(status.optString("pkg", "-")).ifEmpty { "-" }
        val service = if (status.optBoolean("service")) "enabled" else "not enabled"
        val snap = status.optString("snapshot", "-")
        val elements = status.optString("elements", "-")
        val running = if (status.optBoolean("server", false)) "running" else "configured"
        return """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PocketPilot</title>
            <style>
              body{font:15px/1.5 system-ui,sans-serif;margin:24px;max-width:760px;color:#111}
              code{background:#f2f2f2;padding:2px 5px;border-radius:4px}
              .k{color:#666}.row{margin:6px 0}
            </style></head><body>
            <h2>PocketPilot</h2>
            <div class="row"><span class="k">accessibility</span> <b>$service</b></div>
            <div class="row"><span class="k">server</span> <b>$running</b></div>
            <div class="row"><span class="k">foreground app</span> <code>$pkg</code></div>
            <div class="row"><span class="k">last snapshot</span> <code>$snap</code> &middot; <code>$elements</code> elements</div>
            <p>MCP endpoint: <code>POST /mcp</code><br>
            Quick look: <a href="/v1/observe?fmt=text">/v1/observe?fmt=text</a><br>
            Machine status: <a href="/v1/status">/v1/status</a></p>
            <p>Connect from a computer with <code>adb forward tcp:8787 tcp:8787</code>, then point an MCP client at
            <code>http://127.0.0.1:8787/mcp</code>.</p>
            </body></html>
        """.trimIndent()
    }

    private fun escapeHtml(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
