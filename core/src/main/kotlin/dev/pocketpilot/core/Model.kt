package dev.pocketpilot.core

/**
 * Screen-space rectangle in pixels. Kept independent of android.graphics so the core
 * engine can be unit-tested on a plain JVM with fake devices.
 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersects(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(x: Int, y: Int): Boolean =
        x >= left && x < right && y >= top && y < bottom

    override fun toString(): String = "$left,$top,$right,$bottom"

    companion object {
        val ZERO = Bounds(0, 0, 0, 0)

        fun parse(raw: String?): Bounds? {
            if (raw.isNullOrBlank()) return null
            val p = raw.split(',', ' ', ':')
            if (p.size < 4) return null
            val l = p[0].trim().toIntOrNull() ?: return null
            val t = p[1].trim().toIntOrNull() ?: return null
            val r = p[2].trim().toIntOrNull() ?: return null
            val b = p[3].trim().toIntOrNull() ?: return null
            return Bounds(l, t, r, b)
        }

        fun ofSize(w: Int, h: Int): Bounds = Bounds(0, 0, w, h)
    }
}

/**
 * One accessibility node, already flattened out of the platform tree.
 *
 * [key] is a deterministic identity computed by the device layer from the node's position
 * and view id. It lets the engine resolve an element reference across snapshots without
 * holding on to platform node objects.
 */
data class RawNode(
    val key: String,
    val parent: Int = -1,
    val role: String = "box",
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds.ZERO,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val disabled: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val childCount: Int = 0,
    val depth: Int = 0
) {
    val label: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: desc?.takeIf { it.isNotBlank() }
            ?: hint?.takeIf { it.isNotBlank() }
            ?: ""

    val interactive: Boolean get() = clickable || longClickable || editable || scrollable
}

data class RawSnapshot(
    val packageName: String,
    val activity: String? = null,
    val windowTitle: String? = null,
    val screen: Bounds = Bounds.ZERO,
    val rotation: Int = 0,
    val keyboardVisible: Boolean = false,
    val nodes: List<RawNode> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class AppInfo(val packageName: String, val label: String)

data class ToolResult(
    val ok: Boolean,
    val code: String? = null,
    val message: String? = null,
    val text: String? = null
)
