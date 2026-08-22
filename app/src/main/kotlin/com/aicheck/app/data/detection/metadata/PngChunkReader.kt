package com.aicheck.app.data.detection.metadata

import java.io.File
import java.io.IOException
import java.util.zip.Inflater

/**
 * Minimal PNG text-chunk reader: enough to pull tEXt/zTXt/iTXt keyword/value pairs
 * (where Stable Diffusion–family tools store generation parameters) without pulling
 * in a full imaging library. Returns an empty list for anything that isn't a
 * well-formed PNG rather than throwing — metadata inspection is best-effort and must
 * never be the reason the whole analysis fails.
 */
object PngChunkReader {
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    data class TextChunk(val keyword: String, val value: String)

    fun readTextChunks(file: File): List<TextChunk> {
        return try {
            file.inputStream().use { readTextChunks(it.readBytes()) }
        } catch (io: IOException) {
            emptyList()
        }
    }

    private fun readTextChunks(bytes: ByteArray): List<TextChunk> {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            return emptyList()
        }
        val chunks = mutableListOf<TextChunk>()
        var offset = 8
        while (offset + 8 <= bytes.size) {
            val length = readInt32BigEndian(bytes, offset)
            val typeStart = offset + 4
            if (length < 0 || typeStart + 4 + length + 4 > bytes.size) break
            val type = String(bytes, typeStart, 4, Charsets.US_ASCII)
            val dataStart = typeStart + 4
            val data = bytes.copyOfRange(dataStart, dataStart + length)

            when (type) {
                "tEXt" -> parseTextChunk(data)?.let(chunks::add)
                "zTXt" -> parseCompressedTextChunk(data)?.let(chunks::add)
                "iTXt" -> parseInternationalTextChunk(data)?.let(chunks::add)
                "IEND" -> return chunks
            }

            offset = dataStart + length + 4 // skip CRC
        }
        return chunks
    }

    private fun parseTextChunk(data: ByteArray): TextChunk? {
        val nullIndex = data.indexOf(0)
        if (nullIndex < 0) return null
        val keyword = String(data, 0, nullIndex, Charsets.ISO_8859_1)
        val value = String(data, nullIndex + 1, data.size - nullIndex - 1, Charsets.ISO_8859_1)
        return TextChunk(keyword, value)
    }

    private fun parseCompressedTextChunk(data: ByteArray): TextChunk? {
        val nullIndex = data.indexOf(0)
        if (nullIndex < 0 || nullIndex + 2 > data.size) return null
        val keyword = String(data, 0, nullIndex, Charsets.ISO_8859_1)
        // byte at nullIndex+1 is the compression method (always 0 = zlib/deflate)
        val compressed = data.copyOfRange(nullIndex + 2, data.size)
        val inflated = inflate(compressed) ?: return null
        return TextChunk(keyword, String(inflated, Charsets.ISO_8859_1))
    }

    private fun parseInternationalTextChunk(data: ByteArray): TextChunk? {
        var index = data.indexOf(0)
        if (index < 0) return null
        val keyword = String(data, 0, index, Charsets.ISO_8859_1)
        index += 1
        if (index + 2 > data.size) return null
        val compressionFlag = data[index]
        index += 2 // skip compression flag + compression method
        val langEnd = data.indexOf(0, index)
        if (langEnd < 0) return null
        index = langEnd + 1
        val translatedEnd = data.indexOf(0, index)
        if (translatedEnd < 0) return null
        index = translatedEnd + 1
        val textBytes = data.copyOfRange(index, data.size)
        val value = if (compressionFlag.toInt() == 1) {
            inflate(textBytes)?.toString(Charsets.UTF_8) ?: return null
        } else {
            textBytes.toString(Charsets.UTF_8)
        }
        return TextChunk(keyword, value)
    }

    private fun inflate(compressed: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val output = java.io.ByteArrayOutputStream(compressed.size * 3)
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            output.write(buffer, 0, count)
        }
        inflater.end()
        output.toByteArray()
    } catch (e: Exception) {
        null
    }

    private fun readInt32BigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun ByteArray.indexOf(byte: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == byte) return i
        return -1
    }
}
