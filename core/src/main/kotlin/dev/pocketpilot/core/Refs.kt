package dev.pocketpilot.core

/**
 * Keeps the numeric element references stable while a UI session is alive.
 *
 * Agents address elements by `@ref` instead of coordinates, which keeps prompts tiny and makes
 * actions resilient to layout shifts. A ref is recycled only after its underlying view key has
 * not been seen for several snapshots, so an agent can safely act on an observation from a few
 * seconds ago, yet stale refs fail loudly instead of hitting the wrong widget.
 */
class RefRegistry(private val maxAgeGenerations: Long = 3L) {
    private var generation = 0L
    private var nextRef = 1
    private val byKey = HashMap<String, Int>()
    private val keyByRef = HashMap<Int, String>()
    private val seenAt = HashMap<Int, Long>()

    @Synchronized
    fun begin() {
        generation += 1
    }

    @Synchronized
    fun refFor(key: String): Int {
        val existing = byKey[key]
        if (existing != null) {
            seenAt[existing] = generation
            return existing
        }
        val ref = nextRef
        nextRef += 1
        byKey[key] = ref
        keyByRef[ref] = key
        seenAt[ref] = generation
        return ref
    }

    /** Drops refs whose views have been gone for [maxAgeGenerations] snapshots. */
    @Synchronized
    fun end() {
        if (seenAt.isEmpty()) return
        val stale = seenAt.entries.filter { generation - it.value > maxAgeGenerations }.map { it.key }
        for (ref in stale) {
            keyByRef.remove(ref)?.let { byKey.remove(it) }
            seenAt.remove(ref)
        }
    }

    @Synchronized
    fun keyFor(ref: Int): String? = keyByRef[ref]

    @Synchronized
    fun refCount(): Int = keyByRef.size

    @Synchronized
    fun reset() {
        byKey.clear()
        keyByRef.clear()
        seenAt.clear()
        nextRef = 1
        generation = 0
    }

    companion object {
        /** Accepts 12, "@12", "e12" or "ref12". */
        fun parse(ref: Any?): Int? = when (ref) {
            is Number -> ref.toInt()
            is String -> {
                val cleaned = ref.trim().removePrefix("@").removePrefix("e").removePrefix("ref").trim()
                cleaned.toIntOrNull()
            }
            else -> null
        }
    }
}
