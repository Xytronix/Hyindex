package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class VisualAssetIndexerTest {

    @Test
    fun `indexes accepted rasters from configured root with stable ids and descriptive text`() {
        val env = Env()
        writePng(File(env.rasters, "Icons/Weapons/IronSword.png"))
        writePng(File(env.rasters, "UI/hud_heart.jpg"))
        File(env.rasters, "readme.txt").apply { parentFile.mkdirs(); writeText("not an image") }
        File(env.rasters, "data.json").writeText("{}")

        val result = VisualAssetIndexer(env.ctx).index()

        assertTrue(result.ok, "error: ${result.error}")
        assertEquals("visual", result.corpus)
        assertEquals(2, result.indexed)

        val rows = env.db.query(
            "SELECT id, display_name, file_path, embedding_text, corpus FROM nodes WHERE corpus='visual' ORDER BY id",
        ) { rs ->
            NodeRow(
                id = rs.getString("id"),
                name = rs.getString("display_name"),
                path = rs.getString("file_path"),
                text = rs.getString("embedding_text"),
                corpus = rs.getString("corpus"),
            )
        }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.corpus == "visual" })
        val ids = rows.map { it.id }.toSet()
        assertEquals(ids, VisualAssetIndexer(env.ctx).let {
            it.index()
            env.db.query("SELECT id FROM nodes WHERE corpus='visual'") { rs -> rs.getString("id") }.toSet()
        }, "node ids must be stable across reindex")

        val sword = rows.single { it.path.replace('\\', '/').endsWith("Icons/Weapons/IronSword.png") }
        assertTrue(sword.id.contains("IronSword"), "stable id should include asset name, got ${sword.id}")
        assertTrue(sword.text.contains("visual"), "embedding text names corpus")
        assertTrue(sword.text.contains("IronSword"), "embedding text names the asset")
        assertTrue(sword.text.contains("Icons/Weapons/IronSword.png"), "embedding text includes relative path")
        assertFalse(sword.text.contains("HytaleAssets", ignoreCase = true) && sword.text.contains("boilerplate"), sword.text)


        val visualHnsw = File(env.ctx.indexDir, "hnsw/visual.hnsw")
        assertTrue(visualHnsw.exists(), "visual vectors must land in visual.hnsw")
        assertFalse(File(env.ctx.indexDir, "hnsw/code.hnsw").exists(), "must not write code.hnsw")
        assertFalse(File(env.ctx.indexDir, "hnsw/docs.hnsw").exists(), "must not write docs.hnsw")
        assertFalse(File(env.ctx.indexDir, "hnsw/client.hnsw").exists(), "must not write client.hnsw")
        assertFalse(File(env.ctx.indexDir, "hnsw/gamedata.hnsw").exists(), "must not write gamedata.hnsw")
        env.close()
    }

    @Test
    fun `accepted raster extensions are png jpg jpeg webp gif`() {
        val env = Env()
        writePng(File(env.rasters, "a.png"))
        writePng(File(env.rasters, "b.jpg"))
        writePng(File(env.rasters, "c.JPEG"))
        File(env.rasters, "d.webp").writeBytes(VisualMediaTest.minimalWebpVp8x(2, 2))
        File(env.rasters, "e.gif").writeBytes(VisualMediaTest.minimalGif(2, 2))
        writePng(File(env.rasters, "f.bmp"))
        File(env.rasters, "g.svg").writeText("<svg/>")

        val result = VisualAssetIndexer(env.ctx).index()
        assertTrue(result.ok, "error: ${result.error}")
        val count = env.db.query("SELECT COUNT(*) FROM nodes WHERE corpus='visual'") { it.getInt(1) }.first()
        assertEquals(5, count, "bmp/svg must be rejected; png/jpg/jpeg/webp/gif accepted")
        env.close()
    }

    @Test
    fun `blank raster root is a no-op and writes no visual nodes or hnsw`() {
        val base = Files.createTempDirectory("hyindex-visual-none").toFile()
        val cfg = KnowledgeConfig(embeddingProfiles = mapOf("fake" to com.hyindex.knowledge.core.config.EmbeddingProfile("fake", documentModel = "fake")), corpusEmbeddingProfiles = com.hyindex.knowledge.core.db.Corpus.entries.associate { it.id to "fake" }, indexPath = base.absolutePath,
        activeVersion = "release_0.5.2",
        visualRasterRoot = "",)
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        val result = VisualAssetIndexer(ctx).index()
        assertTrue(result.ok, "error: ${result.error}")
        assertEquals(0, result.indexed)
        val count = db.query("SELECT COUNT(*) FROM nodes WHERE corpus='visual'") { it.getInt(1) }.firstOrNull() ?: 0
        assertEquals(0, count)
        assertFalse(File(ctx.indexDir, "hnsw/visual.hnsw").exists())
        db.close()
    }

    @Test
    fun `BuildAllIndexer runs visual only when requested and never mixes into text corpora`() {
        val env = Env()
        writePng(File(env.rasters, "icon.png"))

        val skipped = BuildAllIndexer(env.ctx, emptyList(), null, setOf("code", "docs"), false)
            .run(force = false, extraPlugins = emptyList())
        assertTrue(skipped.none { it.corpus == "visual" }, "default four-corpus run must not index visual")

        val ran = BuildAllIndexer(env.ctx, emptyList(), null, setOf("visual"), false).run()
        assertTrue(ran.any { it.corpus == "visual" && it.ok && it.indexed >= 1 }, "explicit visual corpus must index: $ran")
        assertTrue(File(env.ctx.indexDir, "hnsw/visual.hnsw").exists())
        assertFalse(File(env.ctx.indexDir, "hnsw/code.hnsw").exists())
        env.close()
    }

    @Test
    fun `gemini pdf bytes reach multimodal documents without conversion`() {
        val pdf = "%PDF-1.4\n1 0 obj<</Type /Page>>endobj\ntrailer\n%%EOF".toByteArray()
        val inspected = VisualMedia.inspect(pdf, "pdf", gemini = true)!!
        val doc = com.hyindex.knowledge.core.embedding.MultimodalDocument(
            text = "visual asset spec",
            imageBytes = inspected.bytes,
            mediaType = inspected.mediaType,
        )
        assertEquals("application/pdf", doc.mediaType)
        assertTrue(doc.imageBytes.contentEquals(pdf))
        assertEquals(null, VisualMedia.inspect(pdf, "pdf", gemini = false))
    }

    @Test
    fun `authorized empty visual source wipes nodes fts provenance and hnsw`() {
        val env = Env()
        writePng(File(env.rasters, "icon.png"))
        assertTrue(VisualAssetIndexer(env.ctx).index().indexed >= 1)
        assertTrue(File(env.ctx.indexDir, "hnsw/visual.hnsw").exists())
        File(env.rasters, "icon.png").delete()
        val result = VisualAssetIndexer(env.ctx).index()
        assertTrue(result.ok)
        assertEquals(0, result.indexed)
        val count = env.db.query("SELECT COUNT(*) FROM nodes WHERE corpus='visual'") { it.getInt(1) }.first()
        assertEquals(0, count)
        assertFalse(File(env.ctx.indexDir, "hnsw/visual.hnsw").exists())
        env.close()
    }

    @Test
    fun `outbound png symlink is not indexed`() {
        val env = Env()
        writePng(File(env.rasters, "ok.png"))
        val outside = Files.createTempDirectory("secret-png").toFile()
        val secret = File(outside, "secret.png")
        writePng(secret)
        Files.createSymbolicLink(File(env.rasters, "leak.png").toPath(), secret.toPath())
        val result = VisualAssetIndexer(env.ctx).index()
        assertTrue(result.ok, result.error)
        val ids = env.db.query("SELECT id FROM nodes WHERE corpus='visual'") { it.getString(1) }
        assertEquals(listOf("visual:ok.png"), ids)
        env.close()
        outside.deleteRecursively()
    }

    @Test
    fun `gemini text embedding 004 fails before reading visual bytes`() {
        val env = Env()
        writePng(File(env.rasters, "ok.png"))
        val cfg = env.cfg.copy(
            embeddingProfiles = mapOf("gemini" to com.hyindex.knowledge.core.config.EmbeddingProfile(provider = "gemini", documentModel = "text-embedding-004")),
            corpusEmbeddingProfiles = env.cfg.corpusEmbeddingProfiles + ("visual" to "gemini"),
        )
        val ctx = IndexContext(cfg, env.db, env.cache, env.log, NoopProgressReporter)
        val result = VisualAssetIndexer(ctx).index()
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("gemini-embedding-2") || result.error!!.contains("multimodal"))
        val count = env.db.query("SELECT COUNT(*) FROM nodes WHERE corpus='visual'") { it.getInt(1) }.first()
        assertEquals(0, count)
        env.close()
    }

    @Test
    fun `wipeVisual clears hashes edges fts provenance and hnsw`() {
        val env = Env()
        writePng(File(env.rasters, "icon.png"))
        assertTrue(VisualAssetIndexer(env.ctx).index().ok)
        env.db.execute(
            "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, target_resolved) VALUES ('visual:icon.png','other','RELATES_TO',1)",
        )
        File(env.rasters, "icon.png").delete()
        VisualAssetIndexer(env.ctx).index()
        assertEquals(0, env.db.query("SELECT COUNT(*) FROM nodes WHERE corpus='visual'") { it.getInt(1) }.first())
        assertEquals(0, env.db.query("SELECT COUNT(*) FROM file_hashes WHERE corpus_type='visual'") { it.getInt(1) }.first())
        assertEquals(0, env.db.query("SELECT COUNT(*) FROM edges WHERE source_id LIKE 'visual:%'") { it.getInt(1) }.first())
        assertFalse(File(env.ctx.indexDir, "hnsw/visual.hnsw").exists())
        env.close()
    }

    @Test
    fun `persist failure rolls back nodes and restores previous hnsw`() {
        val env = Env()
        writePng(File(env.rasters, "icon.png"))
        assertTrue(VisualAssetIndexer(env.ctx).index().ok)
        val idsBefore = env.db.query("SELECT id FROM nodes WHERE corpus='visual'") { it.getString(1) }
        assertEquals(listOf("visual:icon.png"), idsBefore)
        val hnsw = File(env.ctx.indexDir, "hnsw/visual.hnsw")
        val prevBytes = hnsw.readBytes()
        writePng(File(env.rasters, "two.png"))
        val indexer = VisualAssetIndexer(env.ctx)
        indexer.persistTestHook = { error("persist boom") }
        assertThrows<Exception> { indexer.index() }
        val idsAfter = env.db.query("SELECT id FROM nodes WHERE corpus='visual'") { it.getString(1) }
        assertEquals(listOf("visual:icon.png"), idsAfter)
        assertTrue(hnsw.exists())
        assertEquals(prevBytes.size, hnsw.length().toInt())
        env.close()
    }

    @Test
    fun `missing unreadable or symlink visual root does not wipe existing corpus`() {
        val env = Env()
        writePng(File(env.rasters, "icon.png"))
        assertTrue(VisualAssetIndexer(env.ctx).index().ok)
        val hnsw = File(env.ctx.indexDir, "hnsw/visual.hnsw")
        assertTrue(hnsw.exists())

        fun reindex(root: String): com.hyindex.knowledge.core.index.IndexResult {
            val cfg = env.cfg.copy(visualRasterRoot = root)
            val ctx = IndexContext(cfg, env.db, env.cache, env.log, NoopProgressReporter)
            return VisualAssetIndexer(ctx).index()
        }

        val missing = reindex(File(env.base, "no-such-visual-root").absolutePath)
        assertTrue(missing.skipped)
        assertTrue(missing.error!!.contains("missing"))

        val link = File(env.base, "visual-link")
        Files.createSymbolicLink(link.toPath(), env.rasters.toPath())
        val linked = reindex(link.absolutePath)
        assertTrue(linked.skipped)
        assertTrue(linked.error!!.contains("symlink"))
        val locked = File(env.base, "locked-visual").apply { mkdirs() }
        Files.setPosixFilePermissions(locked.toPath(), emptySet())
        try {
            val unread = reindex(locked.absolutePath)
            assertTrue(unread.skipped)
            assertTrue(unread.error!!.contains("unreadable"))
        } finally {
            Files.setPosixFilePermissions(
                locked.toPath(),
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }

        assertEquals(1, env.db.query("SELECT COUNT(*) FROM nodes WHERE corpus='visual'") { it.getInt(1) }.first())
        assertTrue(hnsw.exists())
        env.close()
    }


    private data class NodeRow(
        val id: String,
        val name: String,
        val path: String,
        val text: String,
        val corpus: String,
    )

    private class Env {
        val base: File = Files.createTempDirectory("hyindex-visual-idx").toFile()
        val rasters: File = File(base, "rasters").apply { mkdirs() }
        val cfg: KnowledgeConfig = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "fake" to com.hyindex.knowledge.core.config.EmbeddingProfile(
                    "fake",
                    documentModel = "voyage-multimodal-3.5",
                ),
            ),
            corpusEmbeddingProfiles = com.hyindex.knowledge.core.db.Corpus.entries.associate { it.id to "fake" },
            indexPath = base.absolutePath,
            activeVersion = "release_0.5.2",
            visualRasterRoot = rasters.absolutePath,
        )
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        fun close() {
            db.close()
        }
    }

    private fun writePng(file: File) {
        file.parentFile.mkdirs()
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 0xFF0000)
        img.setRGB(1, 0, 0x00FF00)
        img.setRGB(0, 1, 0x0000FF)
        img.setRGB(1, 1, 0xFFFFFF)
        val ext = file.extension.lowercase().let { if (it == "jpeg") "jpg" else it }
        if (ext == "png" || ext == "jpg" || ext == "gif") {
            ImageIO.write(img, if (ext == "jpg") "jpg" else ext, file)
        } else {
            ImageIO.write(img, "png", file)
        }
        if (!file.exists() || file.length() == 0L) {
            file.writeBytes(MINIMAL_PNG)
        }
    }

    companion object {
        private val MINIMAL_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
            0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
            0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
            0x00, 0x03, 0x00, 0x01, 0x00, 0x05, 0xFE.toByte(), 0xD4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
