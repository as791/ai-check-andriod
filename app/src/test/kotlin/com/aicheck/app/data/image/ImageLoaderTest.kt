package com.aicheck.app.data.image

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Covers the failure paths a share-sheet URI can hit: not an image at all, and a
 * URI that can't be opened — both must fail with a specific, honest
 * [ImageLoadException] rather than crashing the analysis pipeline.
 *
 * [GraphicsMode.Mode.NATIVE] is required here: Robolectric's default ("legacy")
 * BitmapFactory shadow doesn't actually validate image bytes — it fakes a
 * successful decode for arbitrary input, which would make these malformed-image
 * tests pass vacuously (no exception ever thrown) regardless of what ImageLoader
 * does. NATIVE mode routes through Robolectric's real Skia-backed decoder, so
 * decode failures here reflect what a real device would actually do.
 *
 * These assert [ImageLoadException] broadly rather than a specific subtype:
 * whether malformed bytes are classified as [ImageLoadException.Unsupported] (failed
 * bounds decode) or [ImageLoadException.Corrupt] (decode itself throws) depends on
 * decoder-internal behavior that legitimately differs between real Android and
 * Robolectric's simulated BitmapFactory. Both are equally correct, honest outcomes
 * for malformed input — the real contract under test is "never crash, always a
 * specific ImageLoadException," not which exact subtype.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val loader = ImageLoader(context)

    @Test
    fun `garbage bytes that are not an image fail with a specific exception, not a crash`() = runTest {
        val file = File.createTempFile("garbage", ".jpg")
        file.writeBytes(ByteArray(256) { it.toByte() })

        val exception = runCatching { loader.normalize(Uri.fromFile(file)) }.exceptionOrNull()

        assertThat(exception).isInstanceOf(ImageLoadException::class.java)
    }

    @Test
    fun `an empty file fails with a specific exception, not a crash`() = runTest {
        val file = File.createTempFile("empty", ".jpg")

        val exception = runCatching { loader.normalize(Uri.fromFile(file)) }.exceptionOrNull()

        assertThat(exception).isInstanceOf(ImageLoadException::class.java)
    }

    @Test
    fun `a URI pointing at nothing fails rather than crashing`() = runTest {
        val missingFileUri = Uri.fromFile(File(context.cacheDir, "does_not_exist_${System.nanoTime()}.jpg"))

        val exception = runCatching { loader.normalize(missingFileUri) }.exceptionOrNull()

        assertThat(exception).isInstanceOf(ImageLoadException::class.java)
    }
}
