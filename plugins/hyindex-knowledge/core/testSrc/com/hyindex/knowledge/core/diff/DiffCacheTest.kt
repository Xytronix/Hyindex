// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.diff

import com.hyindex.knowledge.core.version.VersionResolver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class DiffCacheTest {
    @Test
    fun `cache files stay hashed under diffs root even for traversal ids`() {
        val base = Files.createTempDirectory("diff-cache").toFile()
        val cache = DiffCache(base)
        val diff = VersionDiff(
            versionA = "../etc/passwd",
            versionB = "/tmp/x",
            computedAt = "now",
            summary = DiffSummary(0, 0, 0, emptyMap()),
            entries = emptyList(),
            relatedPatchNotes = emptyList(),
        )
        cache.put(diff)
        val diffs = File(base, "diffs")
        val files = diffs.listFiles() ?: emptyArray()
        assertEquals(1, files.size)
        assertTrue(files[0].name.matches(Regex("[0-9a-f]{32}--[0-9a-f]{32}\\.diff.json")), files[0].name)
        assertTrue(VersionResolver.containedUnder(diffs, files[0]))
        assertFalse(files[0].path.contains(".."))
        assertNotNull(cache.get("../etc/passwd", "/tmp/x"))
        base.deleteRecursively()
    }
}
