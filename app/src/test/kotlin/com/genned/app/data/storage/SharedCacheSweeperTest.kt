package com.genned.app.data.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure logic, no Android dependency — runs as a plain JUnit test. */
class SharedCacheSweeperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_000_000_000L
    private val maxAge = 15 * 60 * 1000L

    private fun fileAged(name: String, ageMillis: Long): File =
        tempFolder.newFile(name).apply { setLastModified(now - ageMillis) }

    @Test
    fun `deletes files older than the max age`() {
        val old = fileAged("original_old.jpg", maxAge + 1_000)

        val deleted = SharedCacheSweeper.sweep(tempFolder.root, now, maxAge)

        assertThat(deleted).isEqualTo(1)
        assertThat(old.exists()).isFalse()
    }

    @Test
    fun `keeps files younger than the max age`() {
        val fresh = fileAged("result_fresh.png", maxAge - 1_000)
        val old = fileAged("frame_old.jpg", maxAge + 1_000)

        val deleted = SharedCacheSweeper.sweep(tempFolder.root, now, maxAge)

        assertThat(deleted).isEqualTo(1)
        assertThat(fresh.exists()).isTrue()
        assertThat(old.exists()).isFalse()
    }

    @Test
    fun `missing dir returns zero without throwing`() {
        val missing = File(tempFolder.root, "does_not_exist")

        assertThat(SharedCacheSweeper.sweep(missing, now, maxAge)).isEqualTo(0)
    }

    @Test
    fun `does not delete subdirectories`() {
        val subdir = tempFolder.newFolder("nested").apply { setLastModified(now - maxAge - 1_000) }

        val deleted = SharedCacheSweeper.sweep(tempFolder.root, now, maxAge)

        assertThat(deleted).isEqualTo(0)
        assertThat(subdir.exists()).isTrue()
    }
}
