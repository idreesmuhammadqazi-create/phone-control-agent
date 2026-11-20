package dev.pocketpilot.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Accessibility node access has to happen on the service's main thread. Server and agent threads
 * cross over here with a bounded wait so a blocked UI can never wedge the engine forever.
 */
object MainThread {
    private val handler = Handler(Looper.getMainLooper())

    fun <T> call(timeoutMs: Long = 3500, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var value: T? = null
        var thrown: Throwable? = null
        handler.post {
            try {
                value = block()
            } catch (t: Throwable) {
                thrown = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        thrown?.let { throw it }
        return value
    }
}
