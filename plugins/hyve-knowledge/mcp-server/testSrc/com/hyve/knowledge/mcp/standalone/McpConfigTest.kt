// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.mcp.standalone

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpConfigTest {

    @Test
    fun `load returns defaults when no config file or env vars exist`() {
        val config = McpConfig.load()
        assertEquals("openai", config.embeddingProvider)
        assertEquals("", config.embeddingBaseUrl)
        assertEquals("text-embedding-3-large", config.embeddingCodeModel)
        assertEquals("text-embedding-3-large", config.embeddingTextModel)
        assertNull(config.embeddingDimensions)
        assertEquals(10, config.resultsPerCorpus)
        assertEquals(5, config.maxRelatedConnections)
    }

    @Test
    fun `configFilePath points to hyve knowledge directory`() {
        val path = McpConfig.configFilePath().absolutePath
        assertTrue(
            path.contains(".hyve") && path.contains("knowledge") && path.endsWith("mcp-config.json"),
            "Config path should be ~/.hyve/knowledge/mcp-config.json, got: $path"
        )
    }
}
