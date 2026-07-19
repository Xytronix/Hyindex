// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.index

import com.hyve.knowledge.core.config.KnowledgeConfig
import com.hyve.knowledge.core.db.EmbeddingCacheDatabase
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.index.EmbeddingCacheService
import com.hyve.knowledge.core.index.IndexContext
import com.hyve.knowledge.core.logging.StdoutLogProvider
import com.hyve.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FtsTokenizerTest {

    @Test fun `splitIdentifiers appends camelCase sub-words`() {
        val split = FtsTokenizer.splitIdentifiers("registerPlayerEvent")
        assertTrue(split.contains("registerPlayerEvent"), "keeps original: $split")
        assertTrue(split.contains("register"), "splits register: $split")
        assertTrue(split.contains("player"), "splits player: $split")
        assertTrue(split.contains("event"), "splits event: $split")
    }

    @Test fun `splitIdentifiers handles snake and kebab and Pascal`() {
        assertTrue(FtsTokenizer.splitIdentifiers("max_health_points").contains("health"))
        assertTrue(FtsTokenizer.splitIdentifiers("inventory-slot-id").contains("slot"))
        assertTrue(FtsTokenizer.splitIdentifiers("PlayerInventory").contains("inventory"))
    }

    @Test fun `FTS MATCH finds a node whose only occurrence is inside a camelCase identifier`() {
        val tempFile = Files.createTempFile("fts_match_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        FtsTokenizer.populate(
            db, "code",
            listOf(FtsTokenizer.FtsRow("code:a#registerPlayerEvent", "Foo#registerPlayerEvent", "void registerPlayerEvent() {}")),
            splitBody = true,
        )
        val hits = db.query(
            "SELECT node_id FROM nodes_fts WHERE nodes_fts MATCH ? AND corpus = ?", "player", "code",
        ) { it.getString("node_id") }
        assertEquals(listOf("code:a#registerPlayerEvent"), hits)
        db.close()
    }

    @Test fun `populate dedupes rows sharing a node_id to one fts row`() {
        val tempFile = Files.createTempFile("fts_dedupe_", ".db").toFile()
        tempFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(tempFile)
        FtsTokenizer.populate(
            db, "code",
            listOf(
                FtsTokenizer.FtsRow("code:Foo#bar", "Foo#bar", "void bar(int x) {}"),
                FtsTokenizer.FtsRow("code:Foo#bar", "Foo#bar", "void bar(String s) {}"),
            ),
            splitBody = true,
        )
        val count = db.query(
            "SELECT COUNT(*) FROM nodes_fts WHERE node_id = ? AND corpus = ?", "code:Foo#bar", "code",
        ) { it.getInt(1) }.first()
        assertEquals(1, count)
        db.close()
    }

    @Test fun `fts row count matches embedded node count per corpus`() {
        val base = Files.createTempDirectory("hyve-fts-count").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val docs = Files.createTempDirectory("hyve-fts-docroot").toFile()
        java.io.File(docs, "a.md").writeText("# A\n\nFirst doc body.\n")
        java.io.File(docs, "b.md").writeText("# B\n\nSecond doc body.\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)
        DocsIndexer(ctx, docRoots = listOf(docs), includeGithubDocs = false).index()

        val nodeCount = db.query("SELECT COUNT(*) FROM nodes WHERE corpus = 'docs'") { it.getInt(1) }.first()
        val ftsCount = db.query("SELECT COUNT(*) FROM nodes_fts WHERE corpus = 'docs'") { it.getInt(1) }.first()
        assertEquals(nodeCount, ftsCount, "fts rows must match node count")
        assertTrue(nodeCount >= 2)
        db.close()
    }
}
