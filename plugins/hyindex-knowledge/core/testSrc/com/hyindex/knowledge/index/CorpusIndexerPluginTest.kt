// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.CorpusIndexer
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.index.IndexResult
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class CorpusIndexerPluginTest {

    @Test fun `BuildAllIndexer runs ServiceLoader-discovered plugins`() {
        val base = Files.createTempDirectory("hyindex-plugin-test").toFile()
        val cfg = KnowledgeConfig(embeddingProfiles = mapOf("fake" to com.hyindex.knowledge.core.config.EmbeddingProfile("fake", documentModel = "fake")), corpusEmbeddingProfiles = com.hyindex.knowledge.core.db.Corpus.entries.associate { it.id to "fake" }, indexPath = base.absolutePath,
        activeVersion = "test_v1",)
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        val pluginResult = IndexResult("test-plugin", 7, skipped = false, error = null)
        val plugin = object : CorpusIndexer {
            override val corpus = "test-plugin"
            override fun index(ctx: IndexContext) = pluginResult
        }

        val results = BuildAllIndexer(ctx, emptyList(), null, setOf("test-plugin"), false)
            .run(force = false, extraPlugins = listOf(plugin))

        val found = results.firstOrNull { it.corpus == "test-plugin" }
        assertTrue(found != null, "plugin corpus result should be present")
        assertEquals(7, found!!.indexed)
        assertTrue(found.ok)

        db.close()
        base.deleteRecursively()
    }

}
