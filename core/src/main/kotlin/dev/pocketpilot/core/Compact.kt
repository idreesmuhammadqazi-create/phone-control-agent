package dev.pocketpilot.core

import org.json.JSONArray
import org.json.JSONObject

data class CompactConfig(
    val maxNodes: Int = 140,
    val hardMaxNodes: Int = 400,
    val maxTextChars: Int = 100,
    val includeIds: Boolean = true,
    val includeBounds: Boolean = true
)

/** One rendered element. [signature] deliberately excludes bounds so pure movement is not a diff. */
data class Line(
    val ref: Int,
    val parentRef: Int,
    val role: String,
    val flags: String,
    val bounds: Bounds,
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val hint: String?,
    val signature: String,
    val key: String = ""
) {
    fun render(): String {
        val sb = StringBuilder(64)
        sb.append('@').append(ref).append('|')
        if (parentRef > 0) sb.append('@').append(parentRef) else sb.append('-')
        sb.append('|').append(role).append('|').append(flags).append('|')
        sb.append(bounds.left).append(',').append(bounds.top).append(',')
            .append(bounds.width).append(',').append(bounds.height)
        sb.append('|').append(viewId ?: "")
        sb.append('|').append(esc(text))
        sb.append('|').append(esc(desc))
        sb.append('|').append(esc(hint))
        return sb.toString()
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("ref", ref)
            .put("role", role)
            .put("b", JSONArray().put(bounds.left).put(bounds.top).put(bounds.width).put(bounds.height))
        if (parentRef > 0) o.put("parent", parentRef)
        if (flags.isNotEmpty()) o.put("flags", flags)
        if (!viewId.isNullOrEmpty()) o.put("id", viewId)
        if (!text.isNullOrEmpty()) o.put("text", text)
        if (!desc.isNullOrEmpty()) o.put("desc", desc)
        if (!hint.isNullOrEmpty()) o.put("hint", hint)
        return o
    }

    private fun esc(raw: String?): String = escapeField(raw)
}

class Render(
    val id: Long,
    val snapshot: RawSnapshot,
    val lines: List<Line>,
    val text: String,
    val json: JSONArray,
    val tokens: Int,
    val truncated: Boolean,
    val totalNodes: Int
) {
    val byRef: Map<Int, Line> = lines.associateBy { it.ref }

    fun header(): String {
        val w = snapshot.screen.width
        val h = snapshot.screen.height
        val act = snapshot.activity?.replace(Regex("\\s+"), "_")?.take(60)
        val target = if (act.isNullOrEmpty()) snapshot.packageName else "${snapshot.packageName}/$act"
        val sb = StringBuilder()
        sb.append('S').append(id).append(' ').append(target)
        if (w > 0 && h > 0) sb.append(' ').append(w).append('x').append(h)
        sb.append(" r").append(snapshot.rotation)
        sb.append(" kb").append(if (snapshot.keyboardVisible) 1 else 0)
        sb.append(' ').append(lines.size).append('/').append(totalNodes)
        if (truncated) sb.append(" trunc")
        return sb.toString()
    }
}

/**
 * Turns a raw accessibility tree into a token-efficient, flat, parent-linked description.
 *
 * The output is intentionally boring: one line per element, stable refs, short roles, no
 * colours, no images, no redundant containers. Everything an agent needs to decide and act is
 * present; everything else is dropped.
 */
class Compactor(private val config: CompactConfig = CompactConfig()) {

    fun compile(
        snapshot: RawSnapshot,
        refs: RefRegistry,
        id: Long,
        maxOverride: Int? = null,
        withJson: Boolean = false
    ): Render {
        val nodes = snapshot.nodes
        if (nodes.isEmpty()) {
            val header = "S$id ${snapshot.packageName} 0/0"
            return Render(id, snapshot, emptyList(), header, JSONArray(), 8, false, 0)
        }

        val n = nodes.size
        val screen = if (snapshot.screen.isEmpty) Bounds(0, 0, Int.MAX_VALUE, Int.MAX_VALUE) else snapshot.screen

        val children = Array(n) { ArrayList<Int>(2) }
        val roots = ArrayList<Int>()
        for (i in 0 until n) {
            val p = nodes[i].parent
            if (p in 0 until i) children[p].add(i) else roots.add(i)
        }

        val keepable = BooleanArray(n)
        for (i in 0 until n) {
            val node = nodes[i]
            val hasKids = node.childCount > 0 || children[i].isNotEmpty()
            val onScreen = node.bounds.isEmpty || node.bounds.intersects(screen)
            keepable[i] = node.visible && node.depth <= 64 && (hasKids || onScreen)
        }

        // Structural nodes with no label and no action are pure layout noise: splice them out.
        val droppable = BooleanArray(n)
        for (i in 0 until n) {
            val node = nodes[i]
            val labelled = node.label.isNotEmpty()
            droppable[i] = !node.interactive && !labelled && Roles.isStructural(node.role)
        }

        val entries = ArrayList<Entry>(n.coerceAtMost(256))
        visit(children, keepable, droppable, roots, entries)

        // Merge "clickable container + single text child" pairs into one line.
        val merged = HashSet<Int>()
        val adopted = HashMap<Int, String>()
        val childrenOfEntry = HashMap<Int, MutableList<Int>>()
        val entryByNode = HashMap<Int, Entry>()
        for (e in entries) {
            entryByNode[e.idx] = e
            childrenOfEntry.getOrPut(e.parentNode) { ArrayList(2) }.add(e.idx)
        }
        for (e in entries) {
            val node = nodes[e.idx]
            if (node.label.isNotEmpty()) continue
            if (!node.clickable && !node.longClickable) continue
            val direct = childrenOfEntry[e.idx] ?: continue
            if (direct.size != 1) continue
            val childIdx = direct[0]
            val child = nodes[childIdx]
            if (child.interactive || child.label.isEmpty()) continue
            if (!(childrenOfEntry[childIdx]?.isEmpty() ?: true)) continue
            merged.add(childIdx)
            adopted[e.idx] = child.label
        }

        var finalEntries = if (merged.isEmpty()) entries else entries.filter { it.idx !in merged }

        // Recompute parents after merging so refs chain correctly.
        val finalByNode = HashMap<Int, Entry>(finalEntries.size)
        for (e in finalEntries) finalByNode[e.idx] = e

        val limit = (maxOverride ?: config.maxNodes).coerceIn(10, config.hardMaxNodes)
        var truncated = false
        if (finalEntries.size > limit) {
            truncated = true
            val scores = HashMap<Int, Int>(finalEntries.size)
            for (e in finalEntries) scores[e.idx] = score(nodes[e.idx])
            val chosen = HashSet<Int>(limit * 2)
            finalEntries.sortedWith(
                compareByDescending<Entry> { scores[it.idx] ?: 0 }.thenBy { it.idx }
            ).take(limit).forEach { chosen.add(it.idx) }
            // Never orphan an element: pull in every kept ancestor.
            for (e in finalEntries) {
                if (e.idx !in chosen) continue
                var p = nodes[e.idx].parent
                while (p >= 0) {
                    if (finalByNode.containsKey(p)) chosen.add(p)
                    p = nodes[p].parent
                }
            }
            finalEntries = finalEntries.filter { it.idx in chosen }
        }

        val rawLines = ArrayList<Line>(finalEntries.size)
        val jsonArray = JSONArray()
        val keptNodes = HashSet<Int>(finalEntries.size)
        for (e in finalEntries) keptNodes.add(e.idx)
        for (e in finalEntries) {
            val node = nodes[e.idx]
            val ref = refs.refFor(node.key)
            var parentRef = 0
            var p = node.parent
            while (p >= 0) {
                if (p in keptNodes) {
                    parentRef = refs.refFor(nodes[p].key)
                    break
                }
                p = nodes[p].parent
            }

            val isPassword = node.password
            val textOut = if (isPassword) null else clean(node.text)
            val descOut = clean(node.desc)
            val hintOut = clean(node.hint)
            val label = adopted[e.idx]?.let { clean(it) }
            val effectiveText = textOut ?: label?.takeIf { node.text.isNullOrBlank() }
            val flags = flagsOf(node)
            val viewId = if (config.includeIds) node.viewId else null
            val signature = buildString {
                append(node.role).append('|').append(flags).append('|')
                append(viewId ?: "").append('|').append(effectiveText ?: "")
                append('|').append(descOut ?: "").append('|').append(hintOut ?: "")
            }
            rawLines.add(
                Line(
                    ref = ref,
                    parentRef = parentRef,
                    role = node.role,
                    flags = flags,
                    bounds = node.bounds,
                    viewId = viewId,
                    text = if (node.password) "•••" else effectiveText,
                    desc = descOut,
                    hint = hintOut,
                    signature = signature,
                    key = node.key
                )
            )
        }

        val body = StringBuilder(rawLines.size * 56 + 96)
        for (line in rawLines) {
            body.append(line.render()).append('\n')
            if (withJson) jsonArray.put(line.toJson())
        }
        val header = Render(id, snapshot, rawLines, "", jsonArray, 0, truncated, n).header()
        val text = if (body.isEmpty()) header else "$header\n${body.toString().trimEnd()}"
        val tokens = text.length / 4 + 6
        return Render(id, snapshot, rawLines, text, jsonArray, tokens, truncated, n)
    }

    private fun visit(
        children: Array<ArrayList<Int>>,
        keepable: BooleanArray,
        droppable: BooleanArray,
        roots: List<Int>,
        out: MutableList<Entry>
    ) {
        val stack = ArrayDeque<Frame>()
        for (i in roots.indices.reversed()) stack.addLast(Frame(roots[i], -1, 0))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val i = frame.idx
            if (frame.depth > 70) continue
            val keep = keepable[i] && !droppable[i]
            val ancestor = if (keep) i else frame.parentNode
            if (keep) out.add(Entry(i, frame.parentNode))
            val kids = children[i]
            for (k in kids.indices.reversed()) {
                stack.addLast(Frame(kids[k], ancestor, frame.depth + 1))
            }
        }
    }

    private fun score(node: RawNode): Int {
        var s = 0
        if (node.clickable || node.longClickable || node.editable || node.scrollable) s += 4
        if (node.editable) s += 2
        if (node.focused) s += 3
        if (node.label.isNotEmpty()) s += 2
        if (!node.viewId.isNullOrEmpty()) s += 1
        if (node.role == Roles.CONTAINER || node.role == Roles.IMAGE) s -= 2
        return s
    }

    private fun clean(raw: String?): String? {
        if (raw == null) return null
        val t = raw.replace('\u00A0', ' ').replace('\n', ' ').replace('\r', ' ')
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (t.length <= config.maxTextChars) return t
        return t.take(config.maxTextChars - 1) + "…"
    }

    private fun flagsOf(node: RawNode): String {
        val sb = StringBuilder(4)
        if (node.clickable) sb.append('c')
        if (node.longClickable) sb.append('l')
        if (node.editable) sb.append('e')
        if (node.scrollable) sb.append('s')
        if (node.focused) sb.append('f')
        if (node.checked) sb.append('k')
        if (node.selected) sb.append('v')
        if (node.disabled) sb.append('x')
        if (node.password) sb.append('p')
        return sb.toString()
    }

    private data class Entry(val idx: Int, val parentNode: Int)
    private data class Frame(val idx: Int, val parentNode: Int, val depth: Int)

    companion object {
        fun escapeField(raw: String?): String {
            if (raw == null || raw.isEmpty()) return ""
            val sb = StringBuilder(raw.length + 8)
            for (ch in raw) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '|' -> sb.append("\\|")
                    '\n' -> sb.append("\\n")
                    '\r' -> Unit
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}

private fun escapeField(raw: String?): String = Compactor.escapeField(raw)
