// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index

import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.config.EmbeddingProfile
import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.IndexContext
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import com.hyindex.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class RecordGraphExtractionTest {
    @Test
    fun `record relations and compact constructor calls enter the graph`() {
        val base = Files.createTempDirectory("hyindex-record-graph").toFile()
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf("fake" to EmbeddingProfile("fake", documentModel = "fake")),
            corpusEmbeddingProfiles = Corpus.entries.associate { it.id to "fake" },
            indexPath = base.absolutePath,
            activeVersion = "release_test",
        )
        val source = config.resolvedIndexPath()
            .resolve("decompiled/com/hypixel/hytale/RecordGraph.java")
        source.parentFile.mkdirs()
        source.writeText(
            """
            package com.hypixel.hytale;
            interface Marker {}
            final class Helper { static void check(String value) {} }
            record Item(String value) implements Marker {
                Item {
                    Helper.check(value);
                }
            }
            """.trimIndent(),
        )
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(config.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(
            EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log),
            log,
        )
        val context = IndexContext(config, db, cache, log, NoopProgressReporter)

        assertTrue(CodeIndexer(context).index().ok)

        val implements = db.query(
            "SELECT target_id FROM edges WHERE source_id = ? AND edge_type = 'IMPLEMENTS'",
            "class:com.hypixel.hytale.Item",
        ) { it.getString(1) }
        assertEquals(listOf("class:com.hypixel.hytale.Marker"), implements)

        val calls = db.query(
            "SELECT target_id FROM edges WHERE source_id = ? AND edge_type = 'CALLS' AND target_resolved = 1",
            "com.hypixel.hytale.Item#<init>",
        ) { it.getString(1) }
        assertTrue("com.hypixel.hytale.Helper#check" in calls, "expected compact constructor call edge, got $calls")
        db.close()
    }
}
