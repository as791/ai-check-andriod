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

    @Test
    fun `extracts the image from an ACTION_SEND with an image mime type`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, imageUri)
        }

        assertThat(ShareIntentParser.extractImageUri(intent)).isEqualTo(imageUri)
    }

    @Test
    fun `ignores an ACTION_SEND that is not an image`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "hello")
        }

        assertThat(ShareIntentParser.extractImageUri(intent)).isNull()
    }

    @Test
    fun `extracts the image from an ACTION_VIEW`() {
        val intent = Intent(Intent.ACTION_VIEW, imageUri)

        assertThat(ShareIntentParser.extractImageUri(intent)).isEqualTo(imageUri)
    }

    @Test
    fun `returns null for a plain launcher intent`() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        assertThat(ShareIntentParser.extractImageUri(intent)).isNull()
    }

    @Test
    fun `returns null for a null intent`() {
        assertThat(ShareIntentParser.extractImageUri(null)).isNull()
    }
}
