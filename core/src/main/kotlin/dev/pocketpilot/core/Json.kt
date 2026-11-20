package dev.pocketpilot.core

import org.json.JSONArray
import org.json.JSONObject

/** Small helpers over org.json, limited to the subset that also exists on Android. */

fun JSONObject.putIfNotEmpty(key: String, value: String?): JSONObject {
    if (!value.isNullOrEmpty()) put(key, value)
    return this
}

fun JSONObject.putIfTrue(key: String, value: Boolean): JSONObject {
    if (value) put(key, true)
    return this
}

fun JSONObject.putIfPositive(key: String, value: Int): JSONObject {
    if (value > 0) put(key, value)
    return this
}

fun JSONObject.str(key: String, fallback: String = ""): String = optString(key, fallback)

fun JSONObject.num(key: String, fallback: Int = 0): Int = optInt(key, fallback)

fun JSONObject.flag(key: String, fallback: Boolean = false): Boolean = optBoolean(key, fallback)

fun JSONObject.child(key: String): JSONObject? = optJSONObject(key)

fun JSONObject.list(key: String): JSONArray? = optJSONArray(key)

fun ok(): JSONObject = JSONObject().put("ok", true)

fun failure(code: String, message: String, hint: String? = null): JSONObject {
    val o = JSONObject()
        .put("ok", false)
        .put("error", code)
        .put("message", message)
    hint?.let { o.put("hint", it) }
    o.put("text", "err $code: $message")
    return o
}

fun JSONObject.asText(pretty: Boolean = false): String =
    try {
        if (pretty) toString(2) else toString()
    } catch (t: Throwable) {
        "{}"
    }
