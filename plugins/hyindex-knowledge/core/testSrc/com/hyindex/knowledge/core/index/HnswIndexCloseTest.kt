// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.index

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class HnswIndexCloseTest {
    @Test
    fun `close unmaps so the hnsw file can be deleted`() {
        val dir = Files.createTempDirectory("hyindex-hnsw-close")
        val path = dir.resolve("code.hnsw")
        val index = HnswIndex(8)
        index.build(listOf(FloatArray(8) { 1f }, FloatArray(8) { 0.5f }))
        index.save(path)
        index.close()
        val loaded = HnswIndex(8)
        loaded.load(path)
        loaded.close()
        assertTrue(Files.deleteIfExists(path))
        assertFalse(Files.exists(path))
    }
}
