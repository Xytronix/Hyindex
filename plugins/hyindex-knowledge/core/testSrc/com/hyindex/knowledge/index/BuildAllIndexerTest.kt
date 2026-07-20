package com.hyindex.knowledge.index

import com.hyindex.common.settings.HytaleVersionDetector
import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class BuildAllIndexerTest {
    @Test fun `runs all corpora, skips missing local ones, writes version meta`() {
        val base = Files.createTempDirectory("hyindex-all").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Demo.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText("package com.hypixel.hytale; public class Demo { public int x(){return 1;} }")
        val docs = Files.createTempDirectory("hyindex-docs").toFile()
        java.io.File(docs, "guide.md").writeText("# Guide\nHow to mod.")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter, assetsZip = null, clientFolder = null, docsDir = docs)
        val info = HytaleVersionDetector.HytaleVersionInfo("release", "0.5.2", "", "rev", "0.5.2")


        val results = BuildAllIndexer(ctx, docRoots = listOf(docs), versionInfo = info, corpora = setOf("code","gamedata","client")).run()

        assertTrue(results.first { it.corpus == "code" }.ok)
        assertTrue(results.first { it.corpus == "gamedata" }.skipped)
        assertTrue(results.first { it.corpus == "client" }.skipped)
        assertTrue(java.io.File(cfg.resolvedIndexPath(), "version_meta.json").exists())
        db.close()
    }

    @Test fun `version_meta json is valid JSON with buildNumber and protocolCrc`() {
        val base = Files.createTempDirectory("hyindex-meta").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_b42_2026-06-23-abc1234")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)
        val info = HytaleVersionDetector.HytaleVersionInfo(
            patchline = "release",
            date = "2026-06-23",
            shortHash = "abc1234",
            fullRevision = "abc1234deadbeef" + "0".repeat(25),
            rawVersion = "b42_2026-06-23-abc1234",
            buildNumber = 42,
            protocolCrc = 9876543210L,
        )

        BuildAllIndexer(ctx, docRoots = emptyList(), versionInfo = info, corpora = emptySet()).run()

        val metaFile = java.io.File(cfg.resolvedIndexPath(), "version_meta.json")
        assertTrue(metaFile.exists())
        val json = Json.parseToJsonElement(metaFile.readText()).jsonObject
        assertEquals("release", json["patchline"]?.jsonPrimitive?.content)
        assertEquals(42, json["buildNumber"]?.jsonPrimitive?.content?.toInt())
        assertEquals(9876543210L, json["protocolCrc"]?.jsonPrimitive?.content?.toLong())
        assertTrue(json.containsKey("indexedAt"))
        assertTrue(json.containsKey("fullRevision"))
        assertTrue(json.containsKey("slug"))
        assertEquals("release", json["branch"]?.jsonPrimitive?.content)

        db.close()
        base.deleteRecursively()
    }
}
