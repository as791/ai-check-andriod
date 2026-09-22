package com.aicheck.app.debug

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Grabs this app's own recent logcat output for on-device debugging, without
 * needing a computer/adb or any special permission. Android has always let an
 * app read log lines it emitted itself via the `logcat` binary — it's only
 * *other* apps'/the system's log that needs the OS-protected READ_LOGS
 * permission (which a normal app can't be granted without adb anyway). Backs
 * the Settings "Copy overlay debug log" button, so a user hitting an overlay
 * bug (bubble not showing over some app, unexpected battery use — see
 * docs/ARCHITECTURE.md "Screen overlay (experimental)") can hand over real
 * evidence entirely from their phone.
 */
object LogcatCapture {

    /**
     * Returns the most recent lines emitted under [tags] (debug level and
     * above), oldest first, or a short human-readable explanation if nothing
     * could be captured — this always returns a display-ready string, never
     * throws.
     */
    fun captureRecent(tags: List<String>, maxLines: Int = 1_000): String {
        val filterArgs = tags.flatMap { listOf("$it:D") } + "*:S" // silence everything else
        return try {
            val process = ProcessBuilder(
                listOf("logcat", "-d", "-v", "time", "-t", maxLines.toString()) + filterArgs,
            ).redirectErrorStream(true).start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()

            output.trim().ifEmpty {
                "(No matching log lines yet. Reproduce the issue first - e.g. enable the " +
                    "overlay, use it, wait a bit - then copy the log again.)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture logcat", e)
            "Could not read the device log on this device/Android version: ${e.message}"
        }
    }

    private const val TAG = "LogcatCapture"
}
