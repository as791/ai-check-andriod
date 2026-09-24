package com.genned.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Polls [UsageStatsManager] for the current foreground app package — the
 * standard, Play-Store-accepted mechanism apps like screen-time/parental-control
 * tools use to know "which app is in front right now." This deliberately does
 * NOT use Accessibility Service, which would let it (and would be flagged for
 * being able to) read another app's actual on-screen content; usage stats only
 * ever reveal a package name. See internal-docs/ARCHITECTURE.md "Screen overlay
 * (experimental)".
 */
class ForegroundAppWatcher(private val context: Context) {

    fun watch(pollIntervalMs: Long = 1_500L): Flow<String?> = flow {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var current: String? = null
        var lastEmitted: String? = null
        // Bootstrap only: establish whatever's already in front before the first
        // poll. Every poll after this only looks at the slice of events since the
        // previous poll (see the loop below) - it never re-derives "current" from
        // a rolling fixed-size window again, which was the bug (see applyEvents kdoc).
        var queriedThrough = System.currentTimeMillis() - LOOKBACK_MS
        while (true) {
            val now = System.currentTimeMillis()
            current = applyEvents(readEvents(usageStatsManager, queriedThrough, now), start = current)
            queriedThrough = now

            if (current != lastEmitted) {
                // Left in deliberately (not gated behind a debug flag): this is the
                // one signal that can actually diagnose "bubble doesn't show over
                // app X" reports without device access - see
                // internal-docs/ARCHITECTURE.md "Screen overlay (experimental)". Only ever a
                // package name, never on-screen content (see internal-docs/PRIVACY.md).
                Log.d(TAG, "Foreground package changed: $lastEmitted -> $current")
                lastEmitted = current
                emit(current)
            }
            delay(pollIntervalMs)
        }
    }

    private fun readEvents(usageStatsManager: UsageStatsManager, start: Long, end: Long): List<Pair<Int, String>> {
        val events = usageStatsManager.queryEvents(start, end)
        val event = UsageEvents.Event()
        val result = mutableListOf<Pair<Int, String>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            result += event.eventType to event.packageName
        }
        return result
    }

    companion object {
        val TARGET_PACKAGES = setOf("com.instagram.android", "com.whatsapp", "com.whatsapp.w4b")
        private const val LOOKBACK_MS = 10_000L
        private const val TAG = "ForegroundAppWatcher"

        /**
         * Folds a batch of usage events onto a starting package, carrying it forward
         * unchanged when nothing relevant happened. This must never re-derive
         * "current" from scratch off a bounded time window: Android only emits a
         * MOVE_TO_FOREGROUND event when an app *becomes* foreground, not repeatedly
         * while it stays there, so an app can sit in front for minutes (e.g.
         * watching Reels) without producing a single new event. The previous
         * implementation re-queried a fixed 10s lookback on every poll and returned
         * null whenever that window happened to contain no events - which is
         * exactly what happens once you've been on one app for more than 10 quiet
         * seconds - hiding the bubble over apps that were never actually
         * backgrounded.
         */
        internal fun applyEvents(events: List<Pair<Int, String>>, start: String?): String? {
            var current = start
            for ((eventType, packageName) in events) {
                when (eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> current = packageName
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> if (packageName == current) current = null
                }
            }
            return current
        }
    }
}
