package dev.pocketpilot.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.pocketpilot.core.Bounds
import dev.pocketpilot.core.Classes
import dev.pocketpilot.core.RawNode
import dev.pocketpilot.core.RawSnapshot
import dev.pocketpilot.core.Roles
import java.util.ArrayDeque

/**
 * Shared tree traversal rules. Capture and lookup must agree on exactly how a node key is built,
 * otherwise element refs would point at the wrong widget.
 */
object ServiceTree {
    private val GENERIC_IDS = setOf("content", "background", "root", "android", "action_bar")

    fun keyOf(pkg: String, path: String, className: String?, viewId: String?): String =
        "$pkg|$path|${Classes.shortName(className)}|${viewId ?: ""}"

    fun viewIdOf(node: AccessibilityNodeInfo): String? {
        val raw = try {
            node.viewIdResourceName
        } catch (_: Throwable) {
            null
        } ?: return null
        val short = raw.substringAfterLast('/')
        if (short.isEmpty() || GENERIC_IDS.contains(short)) return null
        return short
    }

    /**
     * Returns the tree the human is looking at. When the IME owns the active root we step to the
     * application window, because typing into the keyboard itself is never the goal.
     */
    fun activeRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val primary = try {
            service.rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val windows = service.windows
            if (windows.isNullOrEmpty()) return primary
            val active = windows.firstOrNull { it.id == primary.windowId }
            if (active?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val app = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                    ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                app?.root ?: primary
            } else {
                primary
            }
        } catch (_: Throwable) {
            primary
        }
    }

    fun imeVisible(service: AccessibilityService): Boolean = try {
        // The IME window is visible but usually not the *active* window, so presence is the signal.
        service.windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: false
    } catch (_: Throwable) {
        false
    }

    @Suppress("DEPRECATION")
    fun screen(service: AccessibilityService): Bounds = try {
        val dm = DisplayMetrics()
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(dm)
        Bounds(0, 0, dm.widthPixels, dm.heightPixels)
    } catch (_: Throwable) {
        val dm = service.resources.displayMetrics
        Bounds(0, 0, dm.widthPixels, dm.heightPixels)
    }

    @Suppress("DEPRECATION")
    fun rotation(service: AccessibilityService): Int = try {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.rotation
    } catch (_: Throwable) {
        0
    }

    fun boundsOf(node: AccessibilityNodeInfo): Bounds {
        val rect = Rect()
        return try {
            node.getBoundsInScreen(rect)
            Bounds(rect.left, rect.top, rect.right, rect.bottom)
        } catch (_: Throwable) {
            Bounds.ZERO
        }
    }
}

object Capture {
    private const val MAX_NODES = 3000

    fun snapshot(service: AccessibilityService): RawSnapshot? {
        val root = ServiceTree.activeRoot(service) ?: return null
        val packageName = try {
            root.packageName?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }

        val nodes = ArrayList<RawNode>(192)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, -1, "r", 0))

        while (stack.isNotEmpty() && nodes.size < MAX_NODES) {
            val frame = stack.removeLast()
            val node = frame.node
            val index = nodes.size
            nodes.add(convert(node, frame.parent, frame.path, frame.depth, packageName))
            val childCount = try {
                node.childCount
            } catch (_: Throwable) {
                0
            }
            for (i in childCount - 1 downTo 0) {
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                stack.addLast(Frame(child, index, frame.path + "/" + i, frame.depth + 1))
            }
            try {
                node.recycle()
            } catch (_: Throwable) {
            }
        }

        val serviceImpl = service as? PilotAccessibilityService
        return RawSnapshot(
            packageName = packageName,
            activity = serviceImpl?.lastWindowClass?.takeIf { it.isNotBlank() },
            windowTitle = null,
            screen = ServiceTree.screen(service),
            rotation = ServiceTree.rotation(service),
            keyboardVisible = ServiceTree.imeVisible(service),
            nodes = nodes,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun convert(
        node: AccessibilityNodeInfo,
        parent: Int,
        path: String,
        depth: Int,
        packageName: String
    ): RawNode {
        val className = safe({ node.className?.toString() }, null)
        val clickable = safe({ node.isClickable }, false)
        val editable = safe({ node.isEditable }, false) || className?.contains("EditText") == true
        val scrollable = safe({ node.isScrollable }, false)
        val bounds = ServiceTree.boundsOf(node)
        return RawNode(
            key = ServiceTree.keyOf(packageName, path, className, ServiceTree.viewIdOf(node)),
            parent = parent,
            role = Roles.infer(className, clickable, editable, scrollable),
            text = safe({ node.text?.toString() }, null),
            desc = safe({ node.contentDescription?.toString() }, null),
            hint = safe({ node.hintText?.toString() }, null),
            viewId = ServiceTree.viewIdOf(node),
            bounds = bounds,
            clickable = clickable,
            longClickable = safe({ node.isLongClickable }, false),
            editable = editable,
            scrollable = scrollable,
            focused = safe({ node.isFocused }, false),
            checked = safe({ node.isChecked }, false),
            selected = safe({ node.isSelected }, false),
            disabled = safe({ !node.isEnabled }, false),
            password = safe({ node.isPassword }, false),
            visible = safe({ node.isVisibleToUser }, true),
            childCount = safe({ node.childCount }, 0),
            depth = depth
        )
    }

    private inline fun <T> safe(block: () -> T, fallback: T): T =
        try {
            block()
        } catch (_: Throwable) {
            fallback
        }

    private data class Frame(
        val node: AccessibilityNodeInfo,
        val parent: Int,
        val path: String,
        val depth: Int
    )
}

object NodeFinder {
    fun find(service: AccessibilityService, key: String): AccessibilityNodeInfo? {
        val root = ServiceTree.activeRoot(service) ?: return null
        val packageName = try {
            root.packageName?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, "r", 0))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            if (frame.depth > 70) continue
            val node = frame.node
            val classShort = try {
                node.className?.toString()
            } catch (_: Throwable) {
                null
            }
            val candidate = ServiceTree.keyOf(packageName, frame.path, classShort, ServiceTree.viewIdOf(node))
            if (candidate == key) {
                while (stack.isNotEmpty()) {
                    try {
                        stack.removeLast().node.recycle()
                    } catch (_: Throwable) {
                    }
                }
                return node
            }
            val count = try {
                node.childCount
            } catch (_: Throwable) {
                0
            }
            for (i in count - 1 downTo 0) {
                val child = try {
                    node.getChild(i)
                } catch (_: Throwable) {
                    null
                } ?: continue
                stack.addLast(Frame(child, frame.path + "/" + i, frame.depth + 1))
            }
            try {
                node.recycle()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private data class Frame(val node: AccessibilityNodeInfo, val path: String, val depth: Int)
}
