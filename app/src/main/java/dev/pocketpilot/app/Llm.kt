package dev.pocketpilot.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI-compatible chat client. Works with OpenAI, OpenRouter, Groq, a LAN llama.cpp/Ollama
 * server (`http://host:11434/v1`), LM Studio, or anything else exposing /chat/completions.
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMs: Int = 60_000
) {
    fun chat(prompt: String): String {
        val endpoint = baseUrl.trim().trimEnd('/') + "/chat/completions"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val payload = JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("max_tokens", 700)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
                )
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("LLM HTTP $code: ${text.take(300)}")
            }
            val response = JSONObject(text)
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalStateException("LLM returned no choices")
            val content = choice.optJSONObject("message")?.opt("content")
            return when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        content.optJSONObject(i)?.optString("text")?.let { append(it) }
                    }
                }
                else -> throw IllegalStateException("LLM returned empty content")
            }
        } finally {
            connection.disconnect()
        }
    }
}
