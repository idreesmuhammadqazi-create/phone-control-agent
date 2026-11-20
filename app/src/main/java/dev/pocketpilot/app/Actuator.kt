package dev.pocketpilot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import dev.pocketpilot.core.AppInfo
import dev.pocketpilot.core.Bounds
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thin, defensive wrapper over the platform accessibility actions. Every path has a gesture
 * fallback because real apps (Flutter, Compose, WebViews, games) routinely expose nodes that lie
 * about being clickable.
 */
class Actuator(private val context: Context, private val service: AccessibilityService) {

    fun click(key: String): Boolean {
        val node = NodeFinder.find(service, key) ?: return false
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && hops < 10) {
            val clickable = try {
                target.isClickable
            } catch (_: Throwable) {
                false
            }
            if (clickable) break
            target = try {
                target.parent
            } catch (_: Throwable) {
                null
            }
            hops++
        }
        val hit = target ?: node
        if (tryAction(hit, AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (hit !== node && tryAction(node, AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapCenter(ServiceTree.boundsOf(hit))
    }

    fun longClick(key: String, durationMs: Long): Boolean {
        val node = NodeFinder.find(service, key) ?: return false
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && hops < 10) {
            val longClickable = try {
                target.isLongClickable || target.isClickable
            } catch (_: Throwable) {
                false
            }
            if (longClickable) break
            target = try {
                target.parent
            } catch (_: Throwable) {
                null
            }
            hops++
        }
        val hit = target ?: node
        if (tryAction(hit, AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val bounds = ServiceTree.boundsOf(hit)
        if (bounds.isEmpty) return false
        val path = Path().apply {
            moveTo(bounds.centerX.toFloat(), bounds.centerY.toFloat())
            lineTo(bounds.centerX + 1f, bounds.centerY.toFloat())
        }
        return gesture(path, durationMs.coerceIn(400L, 2000L))
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y.toFloat())
        }
        return gesture(path, 40L)
    }

    fun longTap(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y.toFloat())
        }
        return gesture(path, durationMs.coerceIn(400L, 2000L))
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(path, durationMs.coerceIn(50L, 3000L))
    }

    fun setText(key: String, text: String, append: Boolean): Boolean {
        val node = NodeFinder.find(service, key) ?: return false
        tryAction(node, AccessibilityNodeInfo.ACTION_FOCUS)
        val current = if (append) {
            try {
                node.text?.toString().orEmpty()
            } catch (_: Throwable) {
                ""
            }
        } else {
            ""
        }
        val value = current + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val set = try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Throwable) {
            false
        }
        if (set) {
            shortSleep(45)
            if (verifyText(node, value)) return true
        }

        // Fallback for fields that ignore ACTION_SET_TEXT but honour paste.
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("pocketpilot", value))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            shortSleep(60)
            if (verifyText(node, value)) return true
        } catch (_: Throwable) {
        }
        return set
    }

    fun scroll(key: String?, direction: String, amount: Float): Boolean {
        val node = key?.let { NodeFinder.find(service, it) }
        var scroller: AccessibilityNodeInfo? = node
        var hops = 0
        while (scroller != null && hops < 12) {
            val scrollable = try {
                scroller.isScrollable
            } catch (_: Throwable) {
                false
            }
            if (scrollable) break
            scroller = try {
                scroller.parent
            } catch (_: Throwable) {
                null
            }
            hops++
        }
        if (scroller != null) {
            val action = if (direction == "up" || direction == "left") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (tryAction(scroller, action)) return true
        }

        val area = node?.let { ServiceTree.boundsOf(it) }?.takeIf { !it.isEmpty }
            ?: ServiceTree.screen(service)
        if (area.isEmpty) return false
        val dx = (area.width * amount / 2f).toInt().coerceAtLeast(8)
        val dy = (area.height * amount / 2f).toInt().coerceAtLeast(8)
        val cx = area.centerX
        val cy = area.centerY
        return when (direction) {
            "up" -> swipe(cx, cy - dy, cx, cy + dy, 280L)
            "left" -> swipe(cx + dx, cy, cx - dx, cy, 280L)
            "right" -> swipe(cx - dx, cy, cx + dx, cy, 280L)
            else -> swipe(cx, cy + dy, cx, cy - dy, 280L)
        }
    }

    fun press(key: String): Boolean = when (key) {
        "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
        "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
        "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        "enter" -> imeEnter()
        else -> false
    }

    fun launch(packageName: String): Boolean {
        val intent = try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Throwable) {
            null
        } ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun apps(): List<AppInfo> = AppIndex.apps(context)

    fun clipboard(text: String): Boolean = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pocketpilot", text))
        true
    } catch (_: Throwable) {
        false
    }

    @TargetApi(Build.VERSION_CODES.R)
    fun screenshotJpeg(maxWidth: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        try {
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                    try {
                        val buffer = shot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)
                        val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (copy != null && copy.width > maxWidth) {
                            val height = (copy.height * (maxWidth.toFloat() / copy.width)).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(copy, maxWidth, height, true)
                            if (scaled !== copy) copy.recycle()
                            bitmap = scaled
                        } else {
                            bitmap = copy
                        }
                    } catch (_: Throwable) {
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
            service.takeScreenshot(Display.DEFAULT_DISPLAY, context.mainExecutor, callback)
        } catch (_: Throwable) {
            return null
        }
        if (!latch.await(3, TimeUnit.SECONDS)) return null
        val result = bitmap ?: return null
        return try {
            val out = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        } finally {
            result.recycle()
        }
    }

    // ------------------------------------------------------------ internals

    private fun tapCenter(bounds: Bounds): Boolean {
        if (bounds.isEmpty) return false
        return tap(bounds.centerX, bounds.centerY)
    }

    private fun global(action: Int): Boolean = try {
        service.performGlobalAction(action)
    } catch (_: Throwable) {
        false
    }

    private fun tryAction(node: AccessibilityNodeInfo, action: Int): Boolean = try {
        node.performAction(action)
    } catch (_: Throwable) {
        false
    }

    private fun gesture(path: Path, durationMs: Long): Boolean = try {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(1L, 5000L))
        service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    } catch (_: Throwable) {
        false
    }

    private fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val root = ServiceTree.activeRoot(service) ?: return false
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        } catch (_: Throwable) {
            null
        } ?: return false
        return try {
            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifyText(node: AccessibilityNodeInfo, expected: String): Boolean = try {
        node.refresh()
        val now = node.text?.toString().orEmpty()
        now == expected || (node.isPassword && now.isNotEmpty())
    } catch (_: Throwable) {
        false
    }

    private fun shortSleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
