package com.aicheck.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Polls [UsageStatsManager] for the current foreground app package — the
 * standard, Play-Store-accepted mechanism apps like screen-time/parental-control
 * tools use to know "which app is in front right now." This deliberately does
 * NOT use Accessibility Service, which would let it (and would be flagged for
 * being able to) read another app's actual on-screen content; usage stats only
 * ever reveal a package name. See docs/ARCHITECTURE.md "Screen overlay
 * (experimental)".
 */
class ForegroundAppWatcher(private val context: Context) {

    fun watch(pollIntervalMs: Long = 1_500L): Flow<String?> = flow {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var lastEmitted: String? = null
        while (true) {
            val current = currentForegroundPackage(usageStatsManager)
            if (current != lastEmitted) {
                lastEmitted = current
                emit(current)
            }
            delay(pollIntervalMs)
        }
    }

    private fun currentForegroundPackage(usageStatsManager: UsageStatsManager): String? {
        val end = System.currentTimeMillis()
        val start = end - LOOKBACK_MS
        val events = usageStatsManager.queryEvents(start, end)
        var lastResumedPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                lastResumedPackage = event.packageName
            } else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND &&
                event.packageName == lastResumedPackage
            ) {
                lastResumedPackage = null
            }
        }
        return lastResumedPackage
    }

    companion object {
        val TARGET_PACKAGES = setOf("com.instagram.android", "com.whatsapp", "com.whatsapp.w4b")
        private const val LOOKBACK_MS = 10_000L
    }
}
