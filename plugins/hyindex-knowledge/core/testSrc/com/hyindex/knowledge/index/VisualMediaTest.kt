// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VisualMediaTest {
    @Test
    fun `valid png dimensions are parsed and malformed png is rejected`() {
        val png = VisualMedia.inspect(minimalPng(3, 4), "png", gemini = false)!!
        assertEquals(12L, png.pixelCount)
        val bad = minimalPng(0, 4)
        assertNull(VisualMedia.inspect(bad, "png", gemini = false))
    }

    @Test
    fun `Gemini accepts at most six PDF pages`() {
        val six = pdf(6)
        assertEquals(6, VisualMedia.inspect(six, "pdf", gemini = true)!!.pageCount)
        assertNull(VisualMedia.inspect(pdf(7), "pdf", gemini = true))
        assertNull(VisualMedia.inspect(six, "pdf", gemini = false))
    }

    @Test
    fun `Gemini rejects WebP without conversion`() {
        assertNull(VisualMedia.inspect("RIFF....WEBP".toByteArray(), "webp", gemini = true))
    }

    private fun minimalPng(width: Int, height: Int): ByteArray = ByteArray(24).also { bytes ->
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(bytes)
        "IHDR".toByteArray().copyInto(bytes, 12)
        putU32(bytes, 16, width)
        putU32(bytes, 20, height)
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun pdf(pages: Int): ByteArray = buildString {
        append("%PDF-1.4\n")
        repeat(pages) { append("$it 0 obj<</Type /Page>>endobj\n") }
        append("%%EOF")
    }.toByteArray()

    companion object {
        internal fun minimalGif(width: Int, height: Int): ByteArray = ByteArray(10).also { bytes ->
            "GIF89a".toByteArray().copyInto(bytes)
            bytes[6] = width.toByte()
            bytes[7] = (width ushr 8).toByte()
            bytes[8] = height.toByte()
            bytes[9] = (height ushr 8).toByte()
        }

        internal fun minimalWebpVp8x(width: Int, height: Int): ByteArray = ByteArray(30).also { bytes ->
            "RIFF".toByteArray().copyInto(bytes)
            "WEBP".toByteArray().copyInto(bytes, 8)
            "VP8X".toByteArray().copyInto(bytes, 12)
            putU24(bytes, 24, width - 1)
            putU24(bytes, 27, height - 1)
        }

        private fun putU24(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
            bytes[offset + 2] = (value ushr 16).toByte()
        }
    }
}
