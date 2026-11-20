package dev.pocketpilot.core

import org.json.JSONObject

sealed class Verdict {
    object Allow : Verdict()
    data class Deny(val code: String, val reason: String) : Verdict()
    data class Confirm(val reason: String, val detail: String?) : Verdict()
}

data class GuardPolicy(
    /** When true the engine may act in any app except [blocklist]. */
    val allowAllPackages: Boolean = true,
    val allowlist: Set<String> = emptySet(),
    val blocklist: Set<String> = emptySet(),
    val requireConfirmForDestructive: Boolean = true,
    val requireConfirmForPasswordInput: Boolean = true,
    val destructivePatterns: List<Regex> = DEFAULT_DESTRUCTIVE,
    val redactPatterns: List<Regex> = DEFAULT_REDACT
) {
    companion object {
        val DEFAULT_DESTRUCTIVE = listOf(
            Regex(
                "\\b(buy now|buy|purchase|checkout|place order|confirm order|pay now|send money|" +
                    "transfer money|withdraw|delete forever|delete|uninstall|factory reset|erase all|" +
                    "wipe data|log out|sign out|deactivate|close account|remove account|freeze card|" +
                    "block card)\\b",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                "\\b(confirm|submit|approve|continue)\\b.{0,24}[\\\$€£¥₹]\\s?\\d",
                RegexOption.IGNORE_CASE
            )
        )

        val DEFAULT_REDACT = listOf(
            Regex("\\b(?:\\d[ -]?){13,19}\\b"),
            Regex("\\b[A-Za-z0-9_\\-]{32,}\\b")
        )

        val READ_ONLY_TOOLS = setOf("observe", "status", "apps", "wait_for", "screenshot", "confirm")
    }
}

/**
 * Policy gate for every mutating action.
 *
 * Two independent things happen here:
 *  - access control: is the current app allowed at all;
 *  - risk control: does this action need a human to say yes (payments, deletes, password entry).
 *
 * Sensitive text is also scrubbed from snapshots so card numbers and long secrets never enter an
 * agent's context, even when they are on screen.
 */
class Guard(policy: GuardPolicy = GuardPolicy()) {

    @Volatile
    var policy: GuardPolicy = policy

    fun checkPackage(pkg: String?): Verdict {
        if (pkg.isNullOrBlank()) return Verdict.Allow
        val p = policy
        if (p.blocklist.contains(pkg)) {
            return Verdict.Deny("package_blocked", "$pkg is blocked by policy")
        }
        if (p.allowAllPackages) return Verdict.Allow
        if (p.allowlist.contains(pkg)) return Verdict.Allow
        return Verdict.Deny(
            "package_not_allowlisted",
            "$pkg is not in the allowlist (add it in PocketPilot or enable allow-all)"
        )
    }

    fun checkAction(tool: String, args: JSONObject, label: String?, password: Boolean, pkg: String?): Verdict {
        if (tool in GuardPolicy.READ_ONLY_TOOLS) return Verdict.Allow

        val access = checkPackage(pkg)
        if (access is Verdict.Deny) return access

        val p = policy
        if (!p.requireConfirmForDestructive && !p.requireConfirmForPasswordInput) return Verdict.Allow

        if (p.requireConfirmForPasswordInput && tool == "type" && password) {
            return Verdict.Confirm("password_input", label?.takeIf { it.isNotBlank() })
        }

        if (p.requireConfirmForDestructive) {
            val probe = if (tool == "type") {
                val typed = args.optString("text", "")
                "${label.orEmpty()} $typed".trim()
            } else {
                label.orEmpty()
            }
            val hit = p.destructivePatterns.firstOrNull { it.containsMatchIn(probe) }
            if (hit != null) {
                val match = hit.find(probe)?.value ?: label.orEmpty()
                return Verdict.Confirm("destructive_action", match.take(80))
            }
            if (tool == "long_press") {
                // Long-press is the usual gateway to destructive context menus.
                return Verdict.Confirm("long_press", label?.takeIf { it.isNotBlank() })
            }
        }
        return Verdict.Allow
    }

    fun redactSnapshot(snapshot: RawSnapshot): RawSnapshot {
        val p = policy
        if (p.redactPatterns.isEmpty()) {
            return if (snapshot.nodes.any { it.password }) {
                snapshot.copy(nodes = snapshot.nodes.map { redactNode(it) })
            } else snapshot
        }
        var changed = false
        val nodes = snapshot.nodes.map { node ->
            val redacted = redactNode(node)
            if (redacted !== node) changed = true
            redacted
        }
        return if (changed) snapshot.copy(nodes = nodes) else snapshot
    }

    fun redactNode(node: RawNode): RawNode {
        var result = node
        if (result.password) {
            result = result.copy(text = null)
        }
        val patterns = policy.redactPatterns
        if (patterns.isNotEmpty()) {
            val text = scrub(result.text, patterns)
            val desc = scrub(result.desc, patterns)
            val hint = scrub(result.hint, patterns)
            if (text != result.text || desc != result.desc || hint != result.hint) {
                result = result.copy(text = text, desc = desc, hint = hint)
            }
        }
        return result
    }

    private fun scrub(value: String?, patterns: List<Regex>): String? {
        if (value == null || value.isEmpty()) return value
        var out: String = value
        for (pattern in patterns) {
            if (pattern.containsMatchIn(out)) out = pattern.replace(out, "[redacted]")
        }
        return out
    }
}

data class Pending(
    val id: String,
    val tool: String,
    val args: JSONObject,
    val reason: String,
    val detail: String?,
    val packageName: String?,
    val createdAt: Long = System.currentTimeMillis()
)

class ConfirmationStore(private val ttlMs: Long = 120_000L, private val maxPending: Int = 8) {
    private var counter = 0
    private val items = LinkedHashMap<String, Pending>()

    @Synchronized
    fun create(
        tool: String,
        args: JSONObject,
        reason: String,
        detail: String?,
        packageName: String?
    ): Pending {
        purge()
        if (items.size >= maxPending) {
            val eldest = items.keys.firstOrNull()
            if (eldest != null) items.remove(eldest)
        }
        counter += 1
        val pending = Pending("c$counter", tool, args, reason, detail, packageName)
        items[pending.id] = pending
        return pending
    }

    @Synchronized
    fun take(id: String): Pending? {
        purge()
        return items.remove(id)
    }

    @Synchronized
    fun peek(): List<Pending> {
        purge()
        return items.values.toList()
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        val expired = items.values.filter { now - it.createdAt > ttlMs }.map { it.id }
        for (id in expired) items.remove(id)
    }
}
