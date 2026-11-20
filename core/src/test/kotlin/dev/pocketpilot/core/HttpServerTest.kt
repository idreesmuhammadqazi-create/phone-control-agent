package dev.pocketpilot.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Exercises the real ServerSocket path used on the phone: request parsing, headers, auth, JSON-RPC
 * framing, REST routes and the one-shot SSE fallback.
 */
class HttpServerTest {
    private val client: HttpClient = HttpClient.newHttpClient()
    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun start(router: Router): Int {
        val instance = HttpServer(0, false, router) { }
        assertNull(instance.start())
        server = instance
        assertTrue(instance.boundPort > 0)
        return instance.boundPort
    }

    private fun get(port: Int, path: String, token: String? = null, accept: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET()
        token?.let { builder.header("Authorization", "Bearer $it") }
        accept?.let { builder.header("Accept", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        port: Int,
        path: String,
        body: String,
        token: String? = null,
        accept: String? = null
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        token?.let { builder.header("Authorization", "Bearer $it") }
        accept?.let { builder.header("Accept", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun healthWorksOverRealSocket() {
        val port = start(Router(FakeEngine()))
        val response = get(port, "/health")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"pocketpilot\""))
    }

    @Test
    fun mcpInitializeWorksOverRealSocket() {
        val port = start(Router(FakeEngine()))
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "initialize")
            .put("params", JSONObject().put("protocolVersion", "2025-06-18"))
        val response = post(port, "/mcp", request.toString())
        assertEquals(200, response.statusCode())
        val body = JSONObject(response.body())
        assertEquals("2025-06-18", body.getJSONObject("result").getString("protocolVersion"))
    }

    @Test
    fun bearerTokenIsEnforced() {
        val port = start(Router(FakeEngine(), token = "secret"))
        assertEquals(401, get(port, "/v1/status").statusCode())
        assertEquals(401, get(port, "/v1/status", token = "wrong").statusCode())
        assertEquals(200, get(port, "/v1/status", token = "secret").statusCode())
        assertEquals(200, get(port, "/health").statusCode())
    }

    @Test
    fun restToolCallWorksOverRealSocket() {
        val engine = FakeEngine()
        val port = start(Router(engine))
        val response = post(port, "/v1/tool/tap", "{\"ref\":4}")
        assertEquals(200, response.statusCode())
        assertEquals(listOf("tap"), engine.calls)
    }

    @Test
    fun sseAcceptGetsEventStreamFrame() {
        val port = start(Router(FakeEngine()))
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 7)
            .put("method", "tools/list")
        val response = post(port, "/mcp", request.toString(), accept = "text/event-stream")
        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/event-stream"))
        assertTrue(response.body().startsWith("event: message"))
        assertTrue(response.body().contains("\"tools\""))
    }

    @Test
    fun corsPreflightAndUnknownRoute() {
        val port = start(Router(FakeEngine()))
        val options = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build()
        val preflight = client.send(options, HttpResponse.BodyHandlers.ofString())
        assertEquals(204, preflight.statusCode())
        assertEquals("*", preflight.headers().firstValue("access-control-allow-origin").orElse(""))
        assertEquals(404, get(port, "/nope").statusCode())
    }

    @Test
    fun notificationGetsAcceptedWithoutBody() {
        val port = start(Router(FakeEngine()))
        val notification = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        val response = post(port, "/mcp", notification.toString())
        assertEquals(202, response.statusCode())
        assertTrue(response.body().isEmpty())
    }
}
