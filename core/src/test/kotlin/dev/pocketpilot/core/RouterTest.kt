package dev.pocketpilot.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private fun rpc(router: Router, method: String, params: JSONObject? = null, id: Int = 1): JSONObject {
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        params?.let { request.put("params", it) }
        val response = router.handleRpc(request.toString())
        assertNotNull(response)
        return JSONObject(response!!)
    }

    @Test
    fun initializeNegotiatesProtocolVersion() {
        val router = Router(FakeEngine())
        val response = rpc(
            router,
            "initialize",
            JSONObject().put("protocolVersion", "2024-11-05")
        )
        val result = response.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertEquals("pocketpilot", result.getJSONObject("serverInfo").getString("name"))
    }

    @Test
    fun initializeFallsBackToLatestVersion() {
        val router = Router(FakeEngine())
        val result = rpc(router, "initialize", JSONObject().put("protocolVersion", "1999-01-01"))
            .getJSONObject("result")
        assertEquals("2025-06-18", result.getString("protocolVersion"))
    }

    @Test
    fun notificationsGetNoResponse() {
        val router = Router(FakeEngine())
        val notification = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized")
        assertNull(router.handleRpc(notification.toString()))
    }

    @Test
    fun toolsListExposesEveryToolWithSchema() {
        val router = Router(FakeEngine())
        val tools = rpc(router, "tools/list").getJSONObject("result").getJSONArray("tools")
        assertEquals(Tools.defs.size, tools.length())
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            assertTrue(tool.has("name"))
            assertTrue(tool.has("description"))
            assertEquals("object", tool.getJSONObject("inputSchema").getString("type"))
        }
    }

    @Test
    fun toolsCallDispatchesToEngine() {
        val engine = FakeEngine()
        val router = Router(engine)
        val params = JSONObject()
            .put("name", "tap")
            .put("arguments", JSONObject().put("ref", 3))
        val response = rpc(router, "tools/call", params)
        val content = response.getJSONObject("result")
            .getJSONArray("content")
            .getJSONObject(0)
        assertEquals("text", content.getString("type"))
        assertEquals("ok tool", content.getString("text"))
        assertEquals(listOf("tap"), engine.calls)
    }

    @Test
    fun toolsCallPropagatesErrorsAsToolResult() {
        val engine = FakeEngine()
        engine.toolResponse = failure("action_failed", "nope")
        val router = Router(engine)
        val params = JSONObject().put("name", "tap").put("arguments", JSONObject())
        val result = rpc(router, "tools/call", params).getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
        assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("nope"))
    }

    @Test
    fun unknownToolIsToolError() {
        val router = Router(FakeEngine())
        val result = rpc(
            router,
            "tools/call",
            JSONObject().put("name", "teleport").put("arguments", JSONObject())
        ).getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
    }

    @Test
    fun unknownMethodIsProtocolError() {
        val router = Router(FakeEngine())
        val response = rpc(router, "tools/teleport")
        assertEquals(-32601, response.getJSONObject("error").getInt("code"))
    }

    @Test
    fun malformedJsonIsParseError() {
        val router = Router(FakeEngine())
        val response = JSONObject(router.handleRpc("{not json")!!)
        assertEquals(-32700, response.getJSONObject("error").getInt("code"))
    }

    @Test
    fun batchRequestsAreAnswered() {
        val router = Router(FakeEngine())
        val batch = JSONArray()
            .put(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"))
            .put(JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"))
        val response = JSONArray(router.handleRpc(batch.toString())!!)
        assertEquals(2, response.length())
    }

    @Test
    fun httpHealthIsOpenAndEverythingElseIsNot() {
        val router = Router(FakeEngine(), token = "secret")
        assertEquals(200, router.handleHttp("GET", "/health", null, "", null).status)
        assertEquals(401, router.handleHttp("GET", "/v1/status", null, "", null).status)
        assertEquals(401, router.handleHttp("GET", "/v1/status", null, "", "Bearer wrong").status)
        assertEquals(200, router.handleHttp("GET", "/v1/status", null, "", "Bearer secret").status)
    }

    @Test
    fun httpMcpRejectsGetStreaming() {
        val router = Router(FakeEngine())
        assertEquals(405, router.handleHttp("GET", "/mcp", null, "", null).status)
    }

    @Test
    fun httpMcpAcceptsJsonRpc() {
        val router = Router(FakeEngine())
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/list")
        val response = router.handleHttp("POST", "/mcp", null, request.toString(), null)
        assertEquals(200, response.status)
        assertTrue(response.body.contains("tools"))
    }

    @Test
    fun httpObserveAcceptsQueryParameters() {
        val router = Router(FakeEngine())
        val response = router.handleHttp("GET", "/v1/observe", "since=2&fmt=text&max=50", "", null)
        assertEquals(200, response.status)
        assertTrue(response.body.contains("S1"))
    }

    @Test
    fun httpRestToolCallDispatches() {
        val engine = FakeEngine()
        val router = Router(engine)
        val response = router.handleHttp("POST", "/v1/tool/tap", null, "{\"ref\":2}", null)
        assertEquals(200, response.status)
        assertEquals(listOf("tap"), engine.calls)
    }

    @Test
    fun httpUnknownRouteIs404() {
        val router = Router(FakeEngine())
        assertEquals(404, router.handleHttp("GET", "/nope", null, "", null).status)
    }

    @Test
    fun httpRootServesStatusPage() {
        val router = Router(FakeEngine())
        val response = router.handleHttp("GET", "/", null, "", null)
        assertEquals(200, response.status)
        assertTrue(response.contentType.contains("text/html"))
        assertTrue(response.body.contains("PocketPilot"))
    }

    @Test
    fun httpOptionsIsPreflightNoContent() {
        val router = Router(FakeEngine())
        assertEquals(204, router.handleHttp("OPTIONS", "/mcp", null, "", null).status)
    }
}
