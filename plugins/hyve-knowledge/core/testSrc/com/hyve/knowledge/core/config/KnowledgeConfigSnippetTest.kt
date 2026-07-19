package com.hyve.knowledge.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class KnowledgeConfigSnippetTest {
    @Test fun defaultIs1500() {
        assertEquals(1500, KnowledgeConfig().snippetMaxLength)
    }

    @Test fun roundTripsThroughFile() {
        val dir = Files.createTempDirectory("kcfg").toFile()
        val file = java.io.File(dir, "mcp-config.json")
        try {
            KnowledgeConfig.writeToFile(KnowledgeConfig(snippetMaxLength = 2222), file)
            val loaded = KnowledgeConfig.loadFromFile(file)!!
            assertEquals(2222, loaded.snippetMaxLength)
        } finally {
            file.delete(); dir.delete()
        }
    }
}
