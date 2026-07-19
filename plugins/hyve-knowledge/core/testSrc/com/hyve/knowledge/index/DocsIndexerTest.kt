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

class DocsIndexerTest {
    @Test fun `incremental docs re-index re-embeds all chunks so the HNSW stays complete`() {
        val base = Files.createTempDirectory("hyve-docs-incr").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val docs = Files.createTempDirectory("hyve-docroot-incr").toFile()
        java.io.File(docs, "a.md").writeText("# A\n\nFirst doc.\n")
        java.io.File(docs, "b.md").writeText("# B\n\nSecond doc.\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)
        assertTrue(DocsIndexer(ctx, docRoots = listOf(docs), includeGithubDocs = false).index().ok)


        java.io.File(docs, "c.md").writeText("# C\n\nThird doc.\n")
        val r2 = DocsIndexer(ctx, docRoots = listOf(docs), includeGithubDocs = false).index()
        assertTrue(r2.ok)
        assertEquals(3, r2.indexed, "incremental re-index must embed ALL chunks, not just changed; got ${r2.indexed}")
        db.close()
    }
    @Test fun `indexes local markdown into docs corpus without network`() {
        val base = Files.createTempDirectory("hyve-docs").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val docs = Files.createTempDirectory("hyve-docroot").toFile()
        java.io.File(docs, "guide.md").writeText("# Guide\n\nHow to mod Hytale.\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        val r = DocsIndexer(ctx, docRoots = listOf(docs), includeGithubDocs = false).index()

        assertTrue(r.ok, "error: ${r.error}")
        val count = db.query("SELECT COUNT(*) FROM nodes WHERE corpus='docs'") { it.getInt(1) }.firstOrNull() ?: 0
        assertTrue(count >= 1, "expected >=1 docs node, got $count")
        db.close()
    }

    @Test fun `dedups verbatim cross-source duplicates keeping the higher-priority source`() {
        val base = Files.createTempDirectory("hyve-docs-dedup").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val repoRoot = java.io.File(Files.createTempDirectory("hyve-dedup-wt").toFile(), "worktrees/release").apply { mkdirs() }
        val localRoot = Files.createTempDirectory("hyve-dedup-local").toFile()

        java.io.File(repoRoot, "guide.md").writeText("Identical verbatim section body.\n")
        java.io.File(localRoot, "copy.md").writeText("Identical verbatim section body.\n")
        java.io.File(localRoot, "other.md").writeText("A different section body.\n")

        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        val r = DocsIndexer(ctx, docRoots = listOf(repoRoot, localRoot), includeGithubDocs = false).index()

        assertTrue(r.ok, "error: ${r.error}")
        val ids = db.query("SELECT id FROM nodes WHERE corpus='docs'") { it.getString("id") }
        assertEquals(2, ids.size, "duplicate collapses; distinct survives; got $ids")
        assertTrue(ids.any { it.startsWith("repo:") }, "kept higher-priority repo copy; got $ids")
        assertTrue(ids.none { it.startsWith("local:") && it.contains("copy.md") }, "dropped lower-priority local copy; got $ids")
        db.close()
    }

    @Test fun `indexes both chunks when two roots share the same relative path`() {
        val base = Files.createTempDirectory("hyve-docs-collision").toFile()
        val cfg = KnowledgeConfig(embeddingProvider = "fake", indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        val root1 = Files.createTempDirectory("hyve-root1").toFile()
        val root2 = Files.createTempDirectory("hyve-root2").toFile()

        java.io.File(root1, "guide.md").writeText("# Guide A\n\nContent from root1.\n")
        java.io.File(root2, "guide.md").writeText("# Guide B\n\nContent from root2.\n")
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(java.io.File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(java.io.File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)

        val r = DocsIndexer(ctx, docRoots = listOf(root1, root2), includeGithubDocs = false).index()

        assertTrue(r.ok, "error: ${r.error}")
        val count = db.query("SELECT COUNT(*) FROM nodes WHERE corpus='docs'") { it.getInt(1) }.firstOrNull() ?: 0
        assertTrue(count == 2, "expected exactly 2 docs nodes (one per root), got $count")
        db.close()
    }
}
