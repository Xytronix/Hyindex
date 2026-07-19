// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.index

import com.hyve.knowledge.core.config.KnowledgeConfig
import com.hyve.knowledge.core.db.EmbeddingCacheDatabase
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.index.EmbeddingCacheService
import com.hyve.knowledge.core.index.IndexContext
import com.hyve.knowledge.core.logging.StdoutLogProvider
import com.hyve.knowledge.core.progress.NoopProgressReporter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class JavadocEnrichmentTest {

    private val methodDoc = "Adds two integers and returns the sum."


    private fun indexDemo(): List<String?> {
        val base = Files.createTempDirectory("hyve-jd").toFile()
        val cfg = KnowledgeConfig(
            embeddingProvider = "fake",
            indexPath = base.absolutePath,
            activeVersion = "release_0.5.2",
        )
        val decompiled = cfg.resolvedIndexPath().resolve("decompiled/com/hypixel/hytale/Demo.java")
        decompiled.parentFile.mkdirs()
        decompiled.writeText(
            """
            package com.hypixel.hytale;
            public class Demo {
                /** $methodDoc */
                public int add(int a, int b) { return a + b; }
            }
            """.trimIndent(),
        )
        val log = StdoutLogProvider
        val db = KnowledgeDatabase.forFile(File(cfg.resolvedIndexPath(), "knowledge.db"), log)
        val cache = EmbeddingCacheService(EmbeddingCacheDatabase.forFile(File(base, "embedding-cache.db"), log), log)
        val ctx = IndexContext(cfg, db, cache, log, NoopProgressReporter)
        try {
            assertTrue(CodeIndexer(ctx).index().ok, "indexing should succeed")
            return db.query("SELECT embedding_text FROM nodes WHERE corpus='code'") {
                it.getString(1)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `embedding text contains inline javadoc`() {
        val rows = indexDemo()
        val matchingRow = rows.firstOrNull { it?.contains(methodDoc) == true }
        assertNotNull(matchingRow, "expected embedding_text to contain the javadoc text; got: $rows")
    }

    @Test
    fun `embedding text does not contain javadoc prefix marker`() {
        val rows = indexDemo()
        assertFalse(
            rows.any { it?.contains("// Javadoc:") == true },
            "embedding_text must not contain the '// Javadoc:' prefix; got: $rows",
        )
    }
}
