package com.aicheck.app.data.detection.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.zip.Deflater

/**
 * Builds minimal, hand-crafted PNG byte streams (no image codec involved) to test
 * [PngChunkReader] against the tEXt/zTXt formats Stable Diffusion–family tools
 * actually use, without depending on a real image file or the Android bitmap stack.
 */
class PngChunkReaderTest {

    @Test
    fun `reads a tEXt chunk containing SD-style generation parameters`() {
        val png = pngWith(textChunk("parameters", "Steps: 20, Sampler: Euler a, CFG scale: 7"))
        val file = writeTempPng(png)

        val chunks = PngChunkReader.readTextChunks(file)

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].keyword).isEqualTo("parameters")
        assertThat(chunks[0].value).contains("Steps: 20")
    }

    @Test
    fun `reads a compressed zTXt chunk`() {
        val png = pngWith(compressedTextChunk("Comment", "Midjourney v6 prompt: a cat"))
        val file = writeTempPng(png)

        val chunks = PngChunkReader.readTextChunks(file)

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].keyword).isEqualTo("Comment")
        assertThat(chunks[0].value).isEqualTo("Midjourney v6 prompt: a cat")
    }

    @Test
    fun `returns an empty list for a file that is not a PNG`() {
        val file = File.createTempFile("not_a_png", ".bin")
        file.writeBytes(ByteArray(32) { 0x42 })

        assertThat(PngChunkReader.readTextChunks(file)).isEmpty()
    }

    @Test
    fun `returns an empty list for a PNG with no text chunks`() {
        val png = pngWith(byteArrayOf()) // just IHDR + IEND, no extra chunk
        val file = writeTempPng(png)

        assertThat(PngChunkReader.readTextChunks(file)).isEmpty()
    }

    private fun writeTempPng(bytes: ByteArray): File =
        File.createTempFile("test", ".png").apply { writeBytes(bytes) }

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngWith(extraChunk: ByteArray): ByteArray {
        val ihdr = chunk("IHDR", ByteArray(13))
        val iend = chunk("IEND", ByteArray(0))
        return pngSignature + ihdr + extraChunk + iend
    }

    private fun textChunk(keyword: String, value: String): ByteArray =
        chunk("tEXt", keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1))

    private fun compressedTextChunk(keyword: String, value: String): ByteArray {
        val compressed = deflate(value.toByteArray(Charsets.ISO_8859_1))
        val data = keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0) + compressed
        return chunk("zTXt", data)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val length = data.size
        val lengthBytes = byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val fakeCrc = ByteArray(4) // PngChunkReader does not validate CRC
        return lengthBytes + typeBytes + data + fakeCrc
    }
}
