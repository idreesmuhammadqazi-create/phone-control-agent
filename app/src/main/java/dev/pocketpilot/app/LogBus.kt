package dev.pocketpilot.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bounded in-memory log. Kept deliberately small: this app has to stay comfortable on a 2 GB
 * phone, and logs are for the human watching the screen, not for the agent.
 */
object LogBus {
    private const val MAX = 160
    private val entries = ArrayDeque<String>(MAX)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(message: String) {
        val line = message.take(300)
        try {
            Log.d("PocketPilot", line)
        } catch (_: Throwable) {
        }
        synchronized(entries) {
            entries.addLast("${stamp.format(Date())} $line")
            while (entries.size > MAX) entries.removeFirst()
        }
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Throwable) {
            }
        }
    }

    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    fun listen(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun unlisten(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
