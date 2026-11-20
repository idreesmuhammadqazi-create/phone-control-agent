package dev.pocketpilot.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import dev.pocketpilot.core.AppInfo
import dev.pocketpilot.core.Bounds
import dev.pocketpilot.core.Device
import dev.pocketpilot.core.RawSnapshot

class AndroidDevice(private val context: Context) : Device {

    private val service: PilotAccessibilityService?
        get() = PilotAccessibilityService.instance

    override fun isReady(): Boolean = service != null

    override fun snapshot(): RawSnapshot? {
        val svc = service ?: return null
        return try {
            MainThread.call(4000) { Capture.snapshot(svc) }
        } catch (_: Throwable) {
            null
        }
    }

    override fun currentPackage(): String? {
        val svc = service ?: return null
        return try {
            MainThread.call(2500) {
                try {
                    svc.rootInActiveWindow?.packageName?.toString()
                } catch (_: Throwable) {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun waitForIdle(maxMs: Long): Boolean {
        val svc = service ?: return false
        val started = System.currentTimeMillis()
        val deadline = started + maxMs
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            // Give slow transitions (activity starts, IME animation) a moment to begin before
            // trusting a quiet window. Otherwise an action can report "no change" too early.
            if (now - started < 200L) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                continue
            }
            if (now - svc.lastEventAt >= 120L) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= 100L) return true
            } else {
                stableSince = 0L
            }
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    override fun tap(x: Int, y: Int): Boolean = act { Actuator(context, it).tap(x, y) }

    override fun longTap(x: Int, y: Int, durationMs: Long): Boolean =
        act { Actuator(context, it).longTap(x, y, durationMs) }

    override fun click(key: String): Boolean = act { Actuator(context, it).click(key) }

    override fun longClick(key: String, durationMs: Long): Boolean =
        act { Actuator(context, it).longClick(key, durationMs) }

    override fun setText(key: String, text: String, append: Boolean): Boolean =
        act { Actuator(context, it).setText(key, text, append) }

    override fun scroll(key: String?, direction: String, amount: Float): Boolean =
        act { Actuator(context, it).scroll(key, direction, amount) }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        act { Actuator(context, it).swipe(x1, y1, x2, y2, durationMs) }

    override fun press(key: String): Boolean = act { Actuator(context, it).press(key) }

    override fun launch(packageName: String): Boolean = act { Actuator(context, it).launch(packageName) }

    override fun apps(): List<AppInfo> = try {
        AppIndex.apps(context)
    } catch (_: Throwable) {
        emptyList()
    }

    override fun clipboard(text: String): Boolean = act { Actuator(context, it).clipboard(text) }

    override fun screenshotJpeg(maxWidth: Int): ByteArray? {
        val svc = service ?: return null
        return try {
            Actuator(context, svc).screenshotJpeg(maxWidth)
        } catch (_: Throwable) {
            null
        }
    }

    private inline fun act(crossinline block: (AccessibilityService) -> Boolean): Boolean {
        val svc = service ?: return false
        return try {
            MainThread.call(4500) { block(svc) } ?: false
        } catch (_: Throwable) {
            false
        }
    }
}
