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

class GameDataIndexerTest {
    @Test fun `skips cleanly when assets zip is absent`() {
        val base = Files.createTempDirectory("hyindex-gd").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null)
        val r = GameDataIndexer(ctx).index()
        assertTrue(r.ok && r.skipped)
        db.close()
    }

    private fun writeZip(file: java.io.File, entries: Map<String, String>) {
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            for ((path, content) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test fun `indexes from gameDataDir when assetsZip is null`() {
        val base = Files.createTempDirectory("hyindex-gd-dir").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val assetsDir = java.io.File(base, "HytaleAssets")
        val itemFile = java.io.File(assetsDir, "Server/Item/Items/Foo.json")
        itemFile.parentFile.mkdirs()
        itemFile.writeText("""{"Id":"Foo"}""")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null, gameDataDir = assetsDir)
        val r = GameDataIndexer(ctx).index()
        assertTrue(r.ok && !r.skipped, "expected ok and not skipped: $r")
        val rows = db.query("SELECT id FROM nodes WHERE corpus='gamedata'") { it.getString(1) }
        assertTrue(rows.isNotEmpty(), "expected at least one gamedata node")
        db.close()
        base.deleteRecursively()
    }

    @Test fun `colliding schema definition names across files both persist as distinct nodes`() {
        val base = Files.createTempDirectory("hyindex-gd-schema-collide").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val assetsDir = java.io.File(base, "HytaleAssets")
        java.io.File(assetsDir, "Schema/a.json").apply { parentFile.mkdirs(); writeText("""{"definitions":{"Color":{"type":"string"}}}""") }
        java.io.File(assetsDir, "Schema/b.json").apply { parentFile.mkdirs(); writeText("""{"definitions":{"Color":{"type":"integer"}}}""") }
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null, gameDataDir = assetsDir)

        assertTrue(GameDataIndexer(ctx).index().ok)

        val count = db.query(
            "SELECT COUNT(*) FROM nodes WHERE corpus='gamedata' AND display_name='Color'"
        ) { it.getInt(1) }.first()
        assertEquals(2, count, "both schema 'Color' definitions must persist, not collapse via INSERT OR REPLACE")
        db.close()
        base.deleteRecursively()
    }

    @Test fun `recovered categories are indexed and Schema paths are excluded`() {
        val base = Files.createTempDirectory("hyindex-gd-recovery").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val assetsDir = java.io.File(base, "HytaleAssets")
        val audioFile = java.io.File(assetsDir, "Server/Audio/SoundEvents/Foo.json")
        audioFile.parentFile.mkdirs()
        audioFile.writeText("""{"Id":"Foo","Volume":1.0}""")
        val miscFile = java.io.File(assetsDir, "Server/Brandnew/Bar.json")
        miscFile.parentFile.mkdirs()
        miscFile.writeText("""{"Id":"Bar"}""")
        val schemaFile = java.io.File(assetsDir, "Schema/Item.json")
        schemaFile.parentFile.mkdirs()
        schemaFile.writeText("""{"Id":"schema"}""")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null, gameDataDir = assetsDir)
        val r = GameDataIndexer(ctx).index()
        assertTrue(r.ok && !r.skipped, "expected ok and not skipped: $r")
        val types = db.query("SELECT data_type FROM nodes WHERE corpus='gamedata'") { it.getString(1) }
        assertTrue(types.contains("audio"), "expected audio data_type but got: $types")
        assertTrue(types.contains("misc"), "expected misc data_type but got: $types")
        assertTrue(!types.contains("schema"), "Schema/ must not be classified as gamedata but got: $types")
        db.close()
        base.deleteRecursively()
    }

    @Test fun `Schema json is indexed with data_type schema`() {
        val base = Files.createTempDirectory("hyindex-gd-schema").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val assetsDir = java.io.File(base, "HytaleAssets")
        val schemaFile = java.io.File(assetsDir, "Schema/Item.json")
        schemaFile.parentFile.mkdirs()
        schemaFile.writeText("""
            {
              "type": "object",
              "${'$'}id": "Item.json",
              "title": "Item",
              "hytale": { "path": "Item/Items", "extension": ".json" },
              "properties": {
                "Parent": { "type": "string", "description": "Inherit from parent." }
              }
            }
        """.trimIndent())
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null, gameDataDir = assetsDir)
        val r = GameDataIndexer(ctx).index()
        assertTrue(r.ok && !r.skipped, "expected ok and not skipped: $r")
        val types = db.query("SELECT data_type FROM nodes WHERE corpus='gamedata'") { it.getString(1) }
        assertTrue(types.contains("schema"), "expected schema data_type but got: $types")
        db.close()
        base.deleteRecursively()
    }

    @Test fun `audio node embedding_text uses bespoke builder not generic`() {
        val base = Files.createTempDirectory("hyindex-gd-audio-bespoke").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val assetsDir = java.io.File(base, "HytaleAssets")
        val audioFile = java.io.File(assetsDir, "Server/Audio/SoundEvents/SFX_Test.json")
        audioFile.parentFile.mkdirs()
        audioFile.writeText("""{"Layers":[{"Files":["Sounds/TEST/Blip.ogg"]}],"MaxDistance":45}""")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null, gameDataDir = assetsDir)
        val r = GameDataIndexer(ctx).index()
        assertTrue(r.ok && !r.skipped, "expected ok and not skipped: $r")
        val embeddingTexts = db.query("SELECT embedding_text FROM nodes WHERE corpus='gamedata' AND data_type='audio'") { it.getString(1) }
        assertTrue(embeddingTexts.isNotEmpty(), "expected audio node")
        val text = embeddingTexts.first() ?: ""
        assertTrue(text.startsWith("Audio:"), "expected bespoke audio text starting with 'Audio:' but got: $text")
        assertTrue(text.contains("Sounds/TEST/Blip.ogg"), "expected file path in audio text but got: $text")
        assertTrue(text.contains("MaxDistance: 45"), "expected MaxDistance in audio text but got: $text")
        db.close()
        base.deleteRecursively()
    }

    @Test fun `indexes plugin manifests from manifestRoot into gamedata`() {
        val base = Files.createTempDirectory("hyindex-gd-manifest").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val assetsDir = java.io.File(base, "HytaleAssets")
        val itemFile = java.io.File(assetsDir, "Server/Item/Items/Foo.json")
        itemFile.parentFile.mkdirs()
        itemFile.writeText("""{"Id":"Foo"}""")
        val manifestRoot = java.io.File(base, "HytaleServer")
        val manifestFile = java.io.File(manifestRoot, "Foo/src/main/resources/manifest.json")
        manifestFile.parentFile.mkdirs()
        manifestFile.writeText("""{"Group":"Hytale","Name":"Foo","Main":"com.example.FooPlugin","Dependencies":{"Hytale:Bar":"*"}}""")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null, gameDataDir = assetsDir, manifestRoot = manifestRoot)
        val r = GameDataIndexer(ctx).index()
        assertTrue(r.ok && !r.skipped, "expected ok and not skipped: $r")
        val types = db.query("SELECT data_type FROM nodes WHERE corpus='gamedata'") { it.getString(1) }
        assertTrue(types.contains("plugin_manifest"), "expected plugin_manifest data_type but got: $types")
        db.close()
        base.deleteRecursively()
    }

    @Test fun `manifest dependencies produce DEPENDS_ON edges with resolution and optional metadata`() {
        val base = Files.createTempDirectory("hyindex-gd-manifest-edges").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val assetsDir = java.io.File(base, "HytaleAssets")
        val itemFile = java.io.File(assetsDir, "Server/Item/Items/Foo.json")
        itemFile.parentFile.mkdirs()
        itemFile.writeText("""{"Id":"Foo"}""")
        val manifestRoot = java.io.File(base, "HytaleServer")
        val manifestA = java.io.File(manifestRoot, "A/src/main/resources/manifest.json")
        manifestA.parentFile.mkdirs()
        manifestA.writeText("""{"Group":"Hytale","Name":"A","Main":"com.example.A","Dependencies":{"Hytale:B":"*"},"OptionalDependencies":{"Hytale:C":"*"}}""")
        val manifestB = java.io.File(manifestRoot, "B/src/main/resources/manifest.json")
        manifestB.parentFile.mkdirs()
        manifestB.writeText("""{"Group":"Hytale","Name":"B","Main":"com.example.B"}""")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null, gameDataDir = assetsDir, manifestRoot = manifestRoot)
        val r = GameDataIndexer(ctx).index()
        assertTrue(r.ok && !r.skipped, "expected ok and not skipped: $r")

        val resolved = db.query(
            "SELECT target_resolved FROM edges WHERE source_id = 'gamedata:manifest:A' AND target_id = 'gamedata:manifest:B' AND edge_type = 'DEPENDS_ON'"
        ) { it.getInt(1) }
        assertEquals(listOf(1), resolved, "B is indexed so DEPENDS_ON A->B must be resolved")

        val optional = db.query(
            "SELECT target_resolved, metadata FROM edges WHERE source_id = 'gamedata:manifest:A' AND target_id = 'gamedata:manifest:C' AND edge_type = 'DEPENDS_ON'"
        ) { it.getInt(1) to it.getString(2) }
        assertEquals(1, optional.size, "expected one optional DEPENDS_ON edge to C")
        assertEquals(0, optional.first().first, "C is not indexed so DEPENDS_ON A->C must be unresolved")
        assertTrue(optional.first().second?.contains("optional") == true, "optional dependency metadata must contain 'optional'")
        db.close()
        base.deleteRecursively()
    }

    @Test fun `incremental gamedata re-index re-embeds all chunks so the HNSW stays complete`() {
        val base = Files.createTempDirectory("hyindex-gd-incr").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val zip = java.io.File(base, "Assets.zip")
        writeZip(zip, mapOf(
            "Server/Item/Items/A.json" to """{"Id":"A"}""",
            "Server/Item/Items/B.json" to """{"Id":"B"}""",
        ))
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = zip)
        assertTrue(GameDataIndexer(ctx).index().ok)


        writeZip(zip, mapOf(
            "Server/Item/Items/A.json" to """{"Id":"A"}""",
            "Server/Item/Items/B.json" to """{"Id":"B"}""",
            "Server/Item/Items/C.json" to """{"Id":"C"}""",
        ))
        val r2 = GameDataIndexer(ctx).index()
        assertTrue(r2.ok)
        assertEquals(3, r2.indexed, "incremental re-index must embed ALL chunks, not just changed; got ${r2.indexed}")
        db.close()
    }
}
