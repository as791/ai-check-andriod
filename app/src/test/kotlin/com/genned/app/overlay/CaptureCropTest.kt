package com.genned.app.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure logic, no Android dependency — runs as a plain JUnit test. */
class CaptureCropTest {

    @Test
    fun `tall portrait screen keeps a full-width square centered in the content area`() {
        // 1080x2400 with a 63px status bar and 126px nav bar: content is 2211px tall.
        val (left, top, size) = CaptureCrop.contentSquare(1080, 2400, topInset = 63, bottomInset = 126)
        assertThat(size).isEqualTo(1080)
        assertThat(left).isEqualTo(0)
        assertThat(top).isEqualTo(63 + (2211 - 1080) / 2)
    }

    @Test
    fun `landscape screen is limited by the content height`() {
        val (left, top, size) = CaptureCrop.contentSquare(2400, 1080, topInset = 60, bottomInset = 20)
        assertThat(size).isEqualTo(1000)
        assertThat(left).isEqualTo((2400 - 1000) / 2)
        assertThat(top).isEqualTo(60)
    }

    @Test
    fun `zero insets use the centered square of the whole frame`() {
        val (left, top, size) = CaptureCrop.contentSquare(1080, 2400, topInset = 0, bottomInset = 0)
        assertThat(size).isEqualTo(1080)
        assertThat(left).isEqualTo(0)
        assertThat(top).isEqualTo((2400 - 1080) / 2)
    }

    @Test
    fun `square always stays inside the frame and below the status bar`() {
        val cases = listOf(
            intArrayOf(1080, 2400, 63, 126),
            intArrayOf(2400, 1080, 60, 20),
            intArrayOf(1080, 1080, 0, 0),
            intArrayOf(720, 1280, 0, 0),
            intArrayOf(1080, 2400, 5000, 5000), // insets larger than the frame
        )
        for ((width, height, topInset, bottomInset) in cases) {
            val (left, top, size) = CaptureCrop.contentSquare(width, height, topInset, bottomInset)
            assertThat(size).isGreaterThan(0)
            assertThat(left).isAtLeast(0)
            assertThat(top).isAtLeast(0)
            assertThat(left + size).isAtMost(width)
            assertThat(top + size).isAtMost(height)
            if (topInset + bottomInset < height) {
                assertThat(top).isAtLeast(topInset)
                assertThat(top + size).isAtMost(height - bottomInset)
            }
        }
    }
}
