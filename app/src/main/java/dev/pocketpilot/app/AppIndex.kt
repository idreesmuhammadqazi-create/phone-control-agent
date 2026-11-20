package dev.pocketpilot.app

import android.content.Context
import android.content.Intent
import dev.pocketpilot.core.AppInfo
import java.util.concurrent.atomic.AtomicReference

/** Launchable-app index with a small cache; package queries are not free on low-end devices. */
object AppIndex {
    private const val TTL_MS = 5 * 60 * 1000L

    private data class Snapshot(val at: Long, val apps: List<AppInfo>)

    private val cache = AtomicReference<Snapshot?>(null)

    fun apps(context: Context): List<AppInfo> {
        val now = System.currentTimeMillis()
        cache.get()?.let { if (now - it.at < TTL_MS) return it.apps }

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Throwable) {
            emptyList()
        }
        val seen = HashSet<String>()
        val apps = ArrayList<AppInfo>(resolved.size)
        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = try {
                info.loadLabel(pm).toString()
            } catch (_: Throwable) {
                pkg
            }
            apps.add(AppInfo(pkg, label))
        }
        apps.sortBy { it.label.lowercase() }
        cache.set(Snapshot(now, apps))
        return apps
    }

    fun invalidate() {
        cache.set(null)
    }
}
