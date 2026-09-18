package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.ContextualDocumentGroups
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.index.SourceChunk
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContextualIndexerGroupingTest {

    private fun ctx(base: File): Triple<KnowledgeConfig, KnowledgeDatabase, IndexContext> {
        val cfg = KnowledgeConfig(embeddingProfiles = mapOf("fake" to com.hyindex.knowledge.core.config.EmbeddingProfile("fake", documentModel = "fake")), corpusEmbeddingProfiles = com.hyindex.knowledge.core.db.Corpus.entries.associate { it.id to "fake" }, indexPath = base.absolutePath,
        activeVersion = "release_0.5.2",)
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log), log)
        return Triple(cfg, db, IndexContext(cfg, db, cache, log, NoopProgressReporter))
    }

    @Test
    fun `code indexer chunks from one owning file form a single Context 4 document`() {
        val base = Files.createTempDirectory("hyindex-ctx-code").toFile()
        val (cfg, db, indexCtx) = ctx(base)
        val decompile = IndexContext.decompileDirFor(cfg).apply { mkdirs() }
        File(decompile, "com/hypixel/hytale/Foo.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.hypixel.hytale;
                public class Foo {
                    public void alpha() { int a = 1; }
                    public void beta() { int b = 2; }
                }
                """.trimIndent(),
            )
        }
        File(decompile, "com/hypixel/hytale/Bar.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.hypixel.hytale;
                public class Bar {
                    public void gamma() { int c = 3; }
                }
                """.trimIndent(),
            )
        }
        assertTrue(CodeIndexer(indexCtx).index().ok)
        val chunks = db.query(
            "SELECT embedding_text, owning_file FROM nodes WHERE corpus='code' AND embedding_text IS NOT NULL ORDER BY id",
        ) { rs -> SourceChunk(Corpus.CODE, rs.getString("embedding_text"), owningFile = rs.getString("owning_file")) }
        val groups = ContextualDocumentGroups.group(chunks)
        assertTrue(groups.size >= 2, "expected at least Foo and Bar groups; got ${groups.size}")
        val foo = groups.single { g -> g.all { it.owningFile!!.endsWith("Foo.java") } }
        assertTrue(foo.size >= 2, "Foo methods must be one document; size=${foo.size}")
        db.close()
    }

    @Test
    fun `docs UI and gamedata indexer chunks group by relative or file path`() {
        val base = Files.createTempDirectory("hyindex-ctx-text").toFile()
        val (cfg, db, indexCtx) = ctx(base)

        val docs = Files.createTempDirectory("hyindex-ctx-docs").toFile()
        File(docs, "guide.md").writeText("# Guide\n\n## One\n\nFirst section.\n\n## Two\n\nSecond section.\n")
        File(docs, "other.md").writeText("# Other\n\nOnly section.\n")
        assertTrue(DocsIndexer(indexCtx, docRoots = listOf(docs), includeGithubDocs = false).index().ok)
        val docChunks = db.query(
            "SELECT embedding_text, file_path FROM nodes WHERE corpus='docs' AND embedding_text IS NOT NULL ORDER BY id",
        ) { rs ->
            SourceChunk(Corpus.DOCS, rs.getString("embedding_text"), relativePath = rs.getString("file_path"))
        }
        val docGroups = ContextualDocumentGroups.group(docChunks)
        assertTrue(docGroups.any { it.size >= 2 }, "guide.md sections must share a document; groups=${docGroups.map { it.size }}")

        val dataDir = File(cfg.resolvedIndexPath(), "Client/Data")
        val uiDir = File(dataDir, "Shared/UI").apply { mkdirs() }
        File(uiDir, "Inventory.xaml").writeText("<Grid><Button/><Label/></Grid>")
        File(uiDir, "Hud.xaml").writeText("<Panel/>")
        val uiCtx = IndexContext(cfg, db, indexCtx.cache, StdoutLogProvider, NoopProgressReporter, clientFolder = dataDir)
        assertTrue(ClientUiIndexer(uiCtx).index().ok)
        val uiChunks = db.query(
            "SELECT embedding_text, file_path FROM nodes WHERE corpus='client' AND embedding_text IS NOT NULL ORDER BY id",
        ) { rs ->
            SourceChunk(Corpus.CLIENT, rs.getString("embedding_text"), relativePath = rs.getString("file_path"))
        }
        val uiGroups = ContextualDocumentGroups.group(uiChunks)
        assertTrue(uiGroups.isNotEmpty())
        assertEquals(
            uiGroups.groupingBy { ContextualDocumentGroups.keyFor(it.first()) }.eachCount().size,
            uiGroups.size,
            "each UI source path is its own document",
        )

        val gdDir = File(cfg.resolvedIndexPath(), "gamedata").apply { mkdirs() }
        val zip = File(gdDir, "assets.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("Server/Item/Sword.json"))
            zos.write("""{"Id":"Sword","Name":"Iron Sword"}""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("Server/Item/Axe.json"))
            zos.write("""{"Id":"Axe","Name":"Iron Axe"}""".toByteArray())
            zos.closeEntry()
        }
        val gdCtx = IndexContext(cfg, db, indexCtx.cache, StdoutLogProvider, NoopProgressReporter, assetsZip = zip)
        assertTrue(GameDataIndexer(gdCtx).index().ok)
        val gdChunks = db.query(
            "SELECT embedding_text, file_path FROM nodes WHERE corpus='gamedata' AND embedding_text IS NOT NULL ORDER BY id",
        ) { rs ->
            SourceChunk(Corpus.GAMEDATA, rs.getString("embedding_text"), filePath = rs.getString("file_path"))
        }
        val gdGroups = ContextualDocumentGroups.group(gdChunks)
        assertTrue(gdGroups.isNotEmpty())
        gdGroups.forEach { group ->
            val paths = group.map { it.filePath }.toSet()
            assertEquals(1, paths.size, "gamedata group must share file path; got $paths")
        }
        db.close()
    }
}
