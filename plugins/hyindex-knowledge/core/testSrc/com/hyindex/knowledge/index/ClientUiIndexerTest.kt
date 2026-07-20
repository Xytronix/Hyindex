package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ClientUiIndexerTest {
    @Test fun `skips cleanly when client folder is absent`() {
        val base = Files.createTempDirectory("hyindex-client").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, clientFolder = null)
        val r = ClientUiIndexer(ctx).index()
        assertTrue(r.ok && r.skipped)
        db.close()
    }

    @Test fun `indexes UI and sibling NodeEditor json, excluding fonts theme language and Licenses`() {
        val base = Files.createTempDirectory("hyindex-client2").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val client = java.io.File(cfg.resolvedIndexPath(), "ClientRoot/Client")
        val dataDir = java.io.File(client, "Data")

        java.io.File(dataDir, "Shared/UI").mkdirs()
        java.io.File(dataDir, "Shared/UI/Foo.xaml").writeText("<Grid/>")
        java.io.File(client, "NodeEditor/Workspaces/Demo").mkdirs()
        java.io.File(client, "NodeEditor/Workspaces/Demo/SensorNode.json").writeText("""{"Id":"Sensor","Title":"Sensor Node"}""")

        java.io.File(dataDir, "Shared/UI/Fonts/NotoSansKR-Medium").mkdirs()
        java.io.File(dataDir, "Shared/UI/Fonts/NotoSansKR-Medium/NotoSansKR-Medium_40.json").writeText("""{"atlas":{"type":"msdf"}}""")
        java.io.File(dataDir, "Shared/UI/Theme").mkdirs()
        java.io.File(dataDir, "Shared/UI/Theme/Colors.json").writeText("""{"primary":"#fff"}""")
        java.io.File(dataDir, "Shared/Language/en-US").mkdirs()
        java.io.File(dataDir, "Shared/Language/en-US/client.lang").writeText("menu.file = File\n")
        java.io.File(client, "NodeEditor/Licenses").mkdirs()
        java.io.File(client, "NodeEditor/Licenses/lic.json").writeText("""{"license":"MIT"}""")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, clientFolder = dataDir)
        val r = ClientUiIndexer(ctx).index()
        assertTrue(r.ok, "indexing should succeed: ${r.error}")
        val types = db.query("SELECT node_type FROM nodes WHERE corpus='client'") { it.getString(1) }
        assertTrue(types.contains("node_schema"), "should index sibling NodeEditor json; got $types")
        val ids = db.query("SELECT id FROM nodes WHERE corpus='client'") { it.getString(1) }
        assertTrue(ids.none { it.contains("Fonts") }, "fonts must be excluded; got $ids")
        assertTrue(ids.none { it.contains("Theme") }, "theme must be excluded; got $ids")
        assertTrue(ids.none { it.contains("Language") || it.endsWith(".lang") }, "language must be excluded; got $ids")
        assertTrue(ids.none { it.contains("Licenses") }, "Licenses must be excluded; got $ids")
        val nodeCount = db.query("SELECT COUNT(*) FROM nodes WHERE corpus='client'") { it.getInt(1) }.first()
        val ftsCount = db.query("SELECT COUNT(*) FROM nodes_fts WHERE corpus='client'") { it.getInt(1) }.first()
        assertEquals(nodeCount, ftsCount, "client fts rows must match node count")
        assertTrue(nodeCount > 0)
        db.close()
    }

    @Test fun `incremental change re-embeds all chunks so the HNSW stays complete`() {
        val base = Files.createTempDirectory("hyindex-client3").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val dataDir = java.io.File(cfg.resolvedIndexPath(), "Client/Data/Shared/UI")
        dataDir.mkdirs()
        java.io.File(dataDir, "A.xaml").writeText("<Grid/>")
        java.io.File(dataDir, "B.xaml").writeText("<Panel/>")
        val clientFolder = java.io.File(cfg.resolvedIndexPath(), "Client/Data")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, clientFolder = clientFolder)
        assertTrue(ClientUiIndexer(ctx).index().ok)

        java.io.File(dataDir, "C.xaml").writeText("<Button/>")
        val r2 = ClientUiIndexer(ctx).index()
        assertTrue(r2.ok)
        assertEquals(3, r2.indexed, "incremental re-index must embed ALL chunks, not just changed; got ${r2.indexed}")
        db.close()
    }
}
