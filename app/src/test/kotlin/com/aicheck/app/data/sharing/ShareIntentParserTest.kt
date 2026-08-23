package com.aicheck.app.data.sharing

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentParserTest {

    private val imageUri: Uri = Uri.parse("content://com.example.app/images/42")
    private val videoUri: Uri = Uri.parse("content://com.example.app/videos/7")

    @Test
    fun `extracts an image from ACTION_SEND`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, imageUri)
        }

        val media = ShareIntentParser.extractSharedMedia(intent)

        assertThat(media).isEqualTo(SharedMedia(imageUri, SharedMediaKind.IMAGE))
    }

    @Test
    fun `extracts a video from ACTION_SEND, e g a Reel or Short`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, videoUri)
        }

        val media = ShareIntentParser.extractSharedMedia(intent)

        assertThat(media).isEqualTo(SharedMedia(videoUri, SharedMediaKind.VIDEO))
    }

    @Test
    fun `ignores an ACTION_SEND that is neither image nor video`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "hello")
        }

        assertThat(ShareIntentParser.extractSharedMedia(intent)).isNull()
    }

    @Test
    fun `extracts the first item from ACTION_SEND_MULTIPLE`() {
        val secondUri = Uri.parse("content://com.example.app/images/43")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(imageUri, secondUri))
        }

        val media = ShareIntentParser.extractSharedMedia(intent)

        assertThat(media).isEqualTo(SharedMedia(imageUri, SharedMediaKind.IMAGE))
    }

    @Test
    fun `extracts a video from ACTION_SEND_MULTIPLE`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(videoUri))
        }

        val media = ShareIntentParser.extractSharedMedia(intent)

        assertThat(media).isEqualTo(SharedMedia(videoUri, SharedMediaKind.VIDEO))
    }

    @Test
    fun `returns null for an empty ACTION_SEND_MULTIPLE`() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>())
        }

        assertThat(ShareIntentParser.extractSharedMedia(intent)).isNull()
    }

    @Test
    fun `extracts the image from an ACTION_VIEW`() {
        val intent = Intent(Intent.ACTION_VIEW, imageUri)

        val media = ShareIntentParser.extractSharedMedia(intent)

        assertThat(media).isEqualTo(SharedMedia(imageUri, SharedMediaKind.IMAGE))
    }

    @Test
    fun `returns null for a plain launcher intent`() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        assertThat(ShareIntentParser.extractSharedMedia(intent)).isNull()
    }

    @Test
    fun `returns null for a null intent`() {
        assertThat(ShareIntentParser.extractSharedMedia(null)).isNull()
    }
}
