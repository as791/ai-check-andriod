package com.aicheck.app.data.image

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Covers the failure paths a share-sheet URI can hit: not an image at all, and a
 * URI that can't be opened — both must fail with a specific, honest
 * [ImageLoadException] rather than crashing the analysis pipeline.
 */
@RunWith(RobolectricTestRunner::class)
class ImageLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val loader = ImageLoader(context)

    @Test
    fun `garbage bytes that are not an image fail as unsupported`() = runTest {
        val file = File.createTempFile("garbage", ".jpg")
        file.writeBytes(ByteArray(256) { it.toByte() })

        val exception = runCatching { loader.normalize(Uri.fromFile(file)) }.exceptionOrNull()

        assertThat(exception).isInstanceOf(ImageLoadException.Unsupported::class.java)
    }

    @Test
    fun `an empty file fails as unsupported rather than crashing`() = runTest {
        val file = File.createTempFile("empty", ".jpg")

        val exception = runCatching { loader.normalize(Uri.fromFile(file)) }.exceptionOrNull()

        assertThat(exception).isInstanceOf(ImageLoadException.Unsupported::class.java)
    }

    @Test
    fun `a URI pointing at nothing fails rather than crashing`() = runTest {
        val missingFileUri = Uri.fromFile(File(context.cacheDir, "does_not_exist_${System.nanoTime()}.jpg"))

        val exception = runCatching { loader.normalize(missingFileUri) }.exceptionOrNull()

        assertThat(exception).isInstanceOf(ImageLoadException::class.java)
    }
}
