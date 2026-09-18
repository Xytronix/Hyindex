package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus

object VisualMedia {
    const val MAX_PDF_PAGES = 6
    const val MAX_PDF_BYTES = 20 * 1024 * 1024

    val VOYAGE_RASTERS = setOf("png", "jpg", "jpeg", "webp", "gif")
    val GEMINI_TYPES = setOf("png", "jpg", "jpeg", "pdf")

    fun usesGemini(config: KnowledgeConfig): Boolean {
        val profile = config.resolvedEmbeddingProfile(Corpus.VISUAL)
        return profile.provider.equals("gemini", ignoreCase = true) &&
            profile.documentModel.contains("gemini-embedding-2", ignoreCase = true)
    }

    fun usesVoyageRaster(config: KnowledgeConfig): Boolean {
        val profile = config.resolvedEmbeddingProfile(Corpus.VISUAL)
        return profile.provider.lowercase() in setOf("voyage", "fake") &&
            profile.documentModel.contains("voyage-multimodal", ignoreCase = true)
    }

    fun isMultimodalConfig(config: KnowledgeConfig): Boolean =
        usesGemini(config) || usesVoyageRaster(config)

    fun acceptedExtensions(config: KnowledgeConfig): Set<String> =
        if (!isMultimodalConfig(config)) emptySet()
        else if (usesGemini(config)) GEMINI_TYPES
        else VOYAGE_RASTERS

    fun mediaType(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }


    fun inspect(bytes: ByteArray, extension: String, gemini: Boolean): AcceptedVisual? {
        val ext = extension.lowercase()
        if (gemini) {
            if (ext !in GEMINI_TYPES) return null
        } else if (ext !in VOYAGE_RASTERS) {
            return null
        }
        val kind = detectKind(bytes) ?: return null
        return when (kind) {
            Kind.PNG -> if (ext == "png") raster(kind, "image/png", bytes, pngSize(bytes)) else null
            Kind.JPEG -> if (ext == "jpg" || ext == "jpeg") raster(kind, "image/jpeg", bytes, jpegSize(bytes)) else null
            Kind.WEBP -> if (!gemini && ext == "webp") raster(kind, "image/webp", bytes, webpSize(bytes)) else null
            Kind.GIF -> if (!gemini && ext == "gif") raster(kind, "image/gif", bytes, gifSize(bytes)) else null
            Kind.PDF -> {
                if (!gemini || ext != "pdf") return null
                if (bytes.size > MAX_PDF_BYTES) return null
                val pages = countPdfPages(bytes)
                if (pages <= 0 || pages > MAX_PDF_PAGES) return null
                AcceptedVisual(kind, "application/pdf", bytes, pageCount = pages, pixelCount = null)
            }
        }
    }

    private fun raster(kind: Kind, mediaType: String, bytes: ByteArray, size: Pair<Int, Int>?): AcceptedVisual? {
        val (w, h) = size ?: return null
        val pixels = pixelCount(w, h) ?: return null
        return AcceptedVisual(kind, mediaType, bytes, pageCount = 1, pixelCount = pixels)
    }

    fun pixelCount(width: Int, height: Int): Long? {
        if (width <= 0 || height <= 0) return null
        val product = width.toLong() * height.toLong()
        if (width != 0 && product / width != height.toLong()) return null
        if (product < 0) return null
        return product
    }

    private fun detectKind(bytes: ByteArray): Kind? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return Kind.PNG
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return Kind.JPEG
        }
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        ) return Kind.WEBP
        if (bytes.size >= 6) {
            val header = String(bytes, 0, 6, Charsets.US_ASCII)
            if (header == "GIF87a" || header == "GIF89a") return Kind.GIF
        }
        if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-") return Kind.PDF
        return null
    }

    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) return null
        val w = u32be(bytes, 16) ?: return null
        val h = u32be(bytes, 20) ?: return null
        if (w <= 0 || h <= 0 || w > Int.MAX_VALUE || h > Int.MAX_VALUE) return null
        return w.toInt() to h.toInt()
    }

    private fun gifSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 10) return null
        val w = u16le(bytes, 6)
        val h = u16le(bytes, 8)
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            var marker = bytes[i + 1].toInt() and 0xFF
            i += 2
            while (marker == 0xFF && i < bytes.size) {
                marker = bytes[i].toInt() and 0xFF
                i++
            }
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7) continue
            if (i + 1 >= bytes.size) return null
            val len = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (len < 2 || i + len > bytes.size) return null
            if (marker in 0xC0..0xC3 || marker in 0xC5..0xC7 || marker in 0xC9..0xCB || marker in 0xCD..0xCF) {
                if (i + 6 >= bytes.size) return null
                val h = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
                val w = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                if (w <= 0 || h <= 0) return null
                return w to h
            }
            i += len
        }
        return null
    }

    private fun webpSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 20) return null
        val fourcc = String(bytes, 12, 4, Charsets.US_ASCII)
        return when (fourcc) {
            "VP8X" -> {
                if (bytes.size < 30) return null
                val w = u24le(bytes, 24) + 1
                val h = u24le(bytes, 27) + 1
                if (w <= 0 || h <= 0) return null
                w to h
            }
            "VP8 " -> {
                val payload = 20
                if (bytes.size < payload + 10) return null
                if (bytes[payload + 3] != 0x9D.toByte() || bytes[payload + 4] != 0x01.toByte() || bytes[payload + 5] != 0x2A.toByte()) {
                    return null
                }
                val w = u16le(bytes, payload + 6) and 0x3FFF
                val h = u16le(bytes, payload + 8) and 0x3FFF
                if (w <= 0 || h <= 0) return null
                w to h
            }
            "VP8L" -> {
                if (bytes.size < 25) return null
                if (bytes[20] != 0x2F.toByte()) return null
                val bits = (bytes[21].toInt() and 0xFF) or
                    ((bytes[22].toInt() and 0xFF) shl 8) or
                    ((bytes[23].toInt() and 0xFF) shl 16) or
                    ((bytes[24].toInt() and 0xFF) shl 24)
                val w = (bits and 0x3FFF) + 1
                val h = ((bits shr 14) and 0x3FFF) + 1
                if (w <= 0 || h <= 0) return null
                w to h
            }
            else -> null
        }
    }

    private fun countPdfPages(bytes: ByteArray): Int {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val page = Regex("""/Type\s*/Page(?![sA-Za-z])""")
        return page.findAll(text).count()
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u24le(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 >= bytes.size) return -1
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun u32be(bytes: ByteArray, offset: Int): Long? {
        if (offset + 3 >= bytes.size) return null
        val v = ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
        return v
    }

    enum class Kind { PNG, JPEG, WEBP, GIF, PDF }

    data class AcceptedVisual(
        val kind: Kind,
        val mediaType: String,
        val bytes: ByteArray,
        val pageCount: Int = 1,
        val pixelCount: Long? = null,
    )
}
