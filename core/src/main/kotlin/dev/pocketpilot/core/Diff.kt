package dev.pocketpilot.core

data class DiffResult(
    val text: String,
    val added: Int,
    val removed: Int,
    val changed: Int
)

/**
 * Computes a minimal, line-oriented delta between two observations. When too much changed the
 * caller should send a full snapshot instead: a diff that is almost as large as the screen costs
 * more than it saves.
 */
object Differ {
    fun diff(prev: Render?, cur: Render, maxLines: Int = 80, threshold: Double = 0.55): DiffResult? {
        if (prev == null) return null
        if (prev.id == cur.id) return null
        if (prev.snapshot.packageName != cur.snapshot.packageName) return null

        val prevByRef = prev.byRef
        val curByRef = cur.byRef
        val added = ArrayList<Line>()
        val removed = ArrayList<Line>()
        val changed = ArrayList<Line>()

        for (line in cur.lines) {
            val old = prevByRef[line.ref]
            if (old == null) added.add(line)
            else if (old.signature != line.signature) changed.add(line)
        }
        for (line in prev.lines) {
            if (!curByRef.containsKey(line.ref)) removed.add(line)
        }

        val total = added.size + removed.size + changed.size
        val budget = prev.lines.size.coerceAtLeast(8) * threshold
        if (total > budget) return null

        val out = ArrayList<String>(total + 4)
        out.add("D${cur.id}<-${prev.id} +${added.size} -${removed.size} ~${changed.size}")
        if (total == 0) {
            out.add("no change")
            return DiffResult(out.joinToString("\n"), 0, 0, 0)
        }

        var emitted = 0
        for (line in added) {
            if (emitted >= maxLines) break
            out.add("+ ${line.render()}")
            emitted++
        }
        for (line in removed) {
            if (emitted >= maxLines) break
            out.add("- ${line.ref}${labelOf(line)}")
            emitted++
        }
        for (line in changed) {
            if (emitted >= maxLines) break
            out.add("~ ${line.render()}")
            emitted++
        }
        if (total > emitted) out.add("… +${total - emitted} more")
        return DiffResult(out.joinToString("\n"), added.size, removed.size, changed.size)
    }

    private fun labelOf(line: Line): String {
        val label = line.text ?: line.desc ?: line.hint
        return if (label.isNullOrBlank()) "" else " \"${label.take(60)}\""
    }
}
