package com.hyindex.knowledge.core.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class IndexContextTest {
    @Test fun `indexDir and decompileDir derive from config active version`() {
        val base = Files.createTempDirectory("hyindex-ctx").toFile()
        val cfg = KnowledgeConfig(indexPath = base.absolutePath, activeVersion = "release_0.5.2")
        assertEquals(base.resolve("versions/release_0.5.2"), cfg.resolvedIndexPath())
        assertEquals(
            base.resolve("versions/release_0.5.2/decompiled"),
            IndexContext.decompileDirFor(cfg),
        )
        assertTrue(IndexResult("code", indexed = 3, skipped = false, error = null).toString().contains("code"))
    }
}
