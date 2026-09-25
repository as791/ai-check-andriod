package com.genned.app.overlay

import kotlin.math.min

/**
 * Picks the part of a full-screen overlay capture that gets classified. The
 * classifier input is square, so squashing a tall screenshot (status bar, nav
 * bar, app chrome and all) into it distorts the image; instead drop the system
 * bars and keep the centered square of the remaining content area.
 *
 * Pure logic, no Android dependency, so it runs as a plain JUnit test.
 */
object CaptureCrop {

    /** Returns `[left, top, size]` of the square to crop from a [width] x [height] frame. */
    fun contentSquare(width: Int, height: Int, topInset: Int, bottomInset: Int): IntArray {
        require(width > 0 && height > 0) { "Empty frame: ${width}x$height" }
        // Clamp so bogus insets can never leave less than one row of content.
        val top = topInset.coerceIn(0, height - 1)
        val bottom = bottomInset.coerceIn(0, height - 1 - top)
        val contentHeight = height - top - bottom
        val size = min(width, contentHeight)
        val left = (width - size) / 2
        val squareTop = top + (contentHeight - size) / 2
        return intArrayOf(left, squareTop, size)
    }
}
