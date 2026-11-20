package dev.pocketpilot.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class PilotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PilotAccessibilityService? = null
            private set
    }

    @Volatile
    var lastEventAt: Long = 0L
        private set

    @Volatile
    var lastWindowClass: String? = null
        private set

    @Volatile
    var lastWindowPackage: String? = null
        private set

    override fun onServiceConnected() {
        instance = this
        lastEventAt = System.currentTimeMillis()
        LogBus.add("accessibility service connected")
        (application as? PilotApplication)?.onAccessibilityReady()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = System.currentTimeMillis()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowClass = event.className?.toString()
            lastWindowPackage = event.packageName?.toString()
        }
    }

    override fun onInterrupt() {
        // Nothing to do: observations are pull-based.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        LogBus.add("accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        LogBus.add("accessibility service destroyed")
        super.onDestroy()
    }
}
