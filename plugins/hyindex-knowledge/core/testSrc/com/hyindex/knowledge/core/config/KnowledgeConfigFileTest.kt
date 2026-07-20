// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class KnowledgeConfigFileTest {

    private lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("knowledge_config_test_").toFile()
        tempDir.deleteOnExit()
    }

    @Test
    fun `writeToFile and loadFromFile round-trips correctly`() {
        val original = KnowledgeConfig(
            embeddingProvider = "voyage",
            embeddingBaseUrl = "https://api.voyageai.com",
            embeddingApiKey = "test-api-key",
            embeddingCodeModel = "voyage-code-3",
            embeddingTextModel = "voyage-3-large",
            embeddingDimensions = 1024,
            indexPath = "/custom/index/path",
            resultsPerCorpus = 15,
            maxRelatedConnections = 8,
        )

        val configFile = File(tempDir, "mcp-config.json")
        KnowledgeConfig.writeToFile(original, configFile)

        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals(original.embeddingProvider, loaded!!.embeddingProvider)
        assertEquals(original.embeddingBaseUrl, loaded.embeddingBaseUrl)
        assertEquals(original.embeddingApiKey, loaded.embeddingApiKey)
        assertEquals(original.embeddingCodeModel, loaded.embeddingCodeModel)
        assertEquals(original.embeddingTextModel, loaded.embeddingTextModel)
        assertEquals(original.embeddingDimensions, loaded.embeddingDimensions)
        assertEquals(original.indexPath, loaded.indexPath)
        assertEquals(original.resultsPerCorpus, loaded.resultsPerCorpus)
        assertEquals(original.maxRelatedConnections, loaded.maxRelatedConnections)
    }

    @Test
    fun `loadFromFile ignores unknown legacy keys`() {
        val configFile = File(tempDir, "legacy.json")
        configFile.writeText(
            """
            {
              "embeddingProvider": "voyage",
              "voyageApiKey": "legacy-key",
              "voyageCodeModel": "legacy-code",
              "voyageTextModel": "legacy-text",
              "ollamaBaseUrl": "http://custom:11434",
              "ollamaCodeModel": "legacy-ollama-code",
              "ollamaTextModel": "legacy-ollama-text"
            }
            """.trimIndent(),
        )
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        val defaults = KnowledgeConfig()
        assertNotNull(loaded)
        assertEquals("voyage", loaded!!.embeddingProvider)
        assertEquals(defaults.embeddingBaseUrl, loaded.embeddingBaseUrl)
        assertEquals(defaults.embeddingApiKey, loaded.embeddingApiKey)
        assertEquals(defaults.embeddingCodeModel, loaded.embeddingCodeModel)
        assertEquals(defaults.embeddingTextModel, loaded.embeddingTextModel)
    }

    @Test
    fun `loadFromFile returns null when file does not exist`() {
        val nonExistent = File(tempDir, "does-not-exist.json")
        val loaded = KnowledgeConfig.loadFromFile(nonExistent)
        assertNull(loaded)
    }

    @Test
    fun `loadFromFile returns null for malformed JSON`() {
        val configFile = File(tempDir, "bad.json")
        configFile.writeText("not json at all {{{")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNull(loaded)
    }

    @Test
    fun `loadFromFile populates defaults for missing fields`() {
        val configFile = File(tempDir, "partial.json")
        configFile.writeText("""{ "embeddingProvider": "voyage" }""")

        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        val defaults = KnowledgeConfig()
        assertEquals("voyage", loaded!!.embeddingProvider)
        assertEquals(defaults.embeddingBaseUrl, loaded.embeddingBaseUrl)
        assertEquals(defaults.embeddingCodeModel, loaded.embeddingCodeModel)
        assertEquals(defaults.resultsPerCorpus, loaded.resultsPerCorpus)
    }

    @Test
    fun `retentionCount defaults to 0`() {
        assertEquals(0, KnowledgeConfig().retentionCount)
    }

    @Test
    fun `retentionCount round-trips through writeToFile and loadFromFile`() {
        val configFile = File(tempDir, "retention.json")
        val original = KnowledgeConfig(retentionCount = 7)
        KnowledgeConfig.writeToFile(original, configFile)
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals(7, loaded!!.retentionCount)
    }

    @Test
    fun `gitToken defaults to null`() {
        assertNull(KnowledgeConfig().gitToken)
    }

    @Test
    fun `gitToken round-trips from JSON file`() {
        val configFile = File(tempDir, "git-token.json")
        configFile.writeText("""{ "gitToken": "ghp_example" }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals("ghp_example", loaded!!.gitToken)
    }

    @Test
    fun `gitToken is null when absent from JSON file`() {
        val configFile = File(tempDir, "no-git-token.json")
        configFile.writeText("""{ "embeddingProvider": "voyage" }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertNull(loaded!!.gitToken)
    }

    @Test
    fun `gitRepoUrl defaults to HypixelStudios hytale-shared-source`() {
        assertEquals(
            "https://github.com/HypixelStudios/hytale-shared-source.git",
            KnowledgeConfig().gitRepoUrl,
        )
    }

    @Test
    fun `gitRepoUrl round-trips from JSON file`() {
        val configFile = File(tempDir, "git-repo.json")
        configFile.writeText("""{ "gitRepoUrl": "https://example.com/hytale.git" }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals("https://example.com/hytale.git", loaded!!.gitRepoUrl)
    }

    @Test
    fun `gitRepoUrl applies default when absent from JSON file`() {
        val configFile = File(tempDir, "no-git-repo.json")
        configFile.writeText("""{ "embeddingProvider": "voyage" }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals(KnowledgeConfig().gitRepoUrl, loaded!!.gitRepoUrl)
    }

    @Test
    fun `gamedata ranking weights round-trip through writeToFile and loadFromFile`() {
        val configFile = File(tempDir, "ranking.json")
        val original = KnowledgeConfig(
            gamedataWorldNodePenalty = 0.3,
            gamedataWorldNodeTypes = listOf("cave", "prefab", "ruin"),
        )
        KnowledgeConfig.writeToFile(original, configFile)
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals(0.3, loaded!!.gamedataWorldNodePenalty)
        assertEquals(listOf("cave", "prefab", "ruin"), loaded.gamedataWorldNodeTypes)
    }

    @Test
    fun `gamedata ranking weights apply defaults when absent from JSON file`() {
        val configFile = File(tempDir, "ranking-defaults.json")
        configFile.writeText("""{ "embeddingProvider": "voyage" }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        val defaults = KnowledgeConfig()
        assertEquals(defaults.gamedataWorldNodePenalty, loaded!!.gamedataWorldNodePenalty)
        assertEquals(defaults.gamedataWorldNodeTypes, loaded.gamedataWorldNodeTypes)
    }

    @Test
    fun `gamedataFetchLimit defaults to 200`() {
        assertEquals(200, KnowledgeConfig().gamedataFetchLimit)
    }

    @Test
    fun `gamedataFetchLimit round-trips through writeToFile and loadFromFile`() {
        val configFile = File(tempDir, "fetch-limit.json")
        val original = KnowledgeConfig(gamedataFetchLimit = 500)
        KnowledgeConfig.writeToFile(original, configFile)
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals(500, loaded!!.gamedataFetchLimit)
    }

    @Test
    fun `gamedataFetchLimit parses from JSON file`() {
        val configFile = File(tempDir, "fetch-limit-json.json")
        configFile.writeText("""{ "gamedataFetchLimit": 350 }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals(350, loaded!!.gamedataFetchLimit)
    }

    @Test
    fun `reranker is disabled by default with rerank-2 dot 5 model`() {
        val config = KnowledgeConfig()
        assertFalse(config.rerankerEnabled)
        assertEquals("rerank-2.5", config.rerankerModel)
        assertEquals(50, config.rerankerTopN)
    }

    @Test
    fun `reranker settings round-trip through writeToFile and loadFromFile`() {
        val configFile = File(tempDir, "reranker.json")
        val original = KnowledgeConfig(
            rerankerEnabled = true,
            rerankerModel = "rerank-2.5",
            rerankerTopN = 25,
        )
        KnowledgeConfig.writeToFile(original, configFile)
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertTrue(loaded!!.rerankerEnabled)
        assertEquals("rerank-2.5", loaded.rerankerModel)
        assertEquals(25, loaded.rerankerTopN)
    }

    @Test
    fun `reranker settings parse from JSON file`() {
        val configFile = File(tempDir, "reranker-json.json")
        configFile.writeText("""{ "rerankerEnabled": true, "rerankerTopN": 30 }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertTrue(loaded!!.rerankerEnabled)
        assertEquals(30, loaded.rerankerTopN)
        assertEquals("rerank-2.5", loaded.rerankerModel)
    }

    @Test
    fun `reranker disabled when absent from JSON file`() {
        val configFile = File(tempDir, "reranker-absent.json")
        configFile.writeText("""{ "embeddingProvider": "voyage" }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertFalse(loaded!!.rerankerEnabled)
    }

    @Test
    fun `hybrid retrieval is enabled by default with RRF k 60`() {
        val config = KnowledgeConfig()
        assertTrue(config.hybridEnabled)
        assertEquals(60, config.hybridRrfK)
        assertEquals(100, config.hybridLexicalLimit)
        assertEquals(10.0, config.hybridNameWeight)
        assertEquals(1.0, config.hybridBodyWeight)
    }

    @Test
    fun `hybrid settings round-trip through writeToFile and loadFromFile`() {
        val configFile = File(tempDir, "hybrid.json")
        val original = KnowledgeConfig(
            hybridEnabled = false,
            hybridRrfK = 30,
            hybridLexicalLimit = 50,
            hybridNameWeight = 8.0,
            hybridBodyWeight = 2.0,
        )
        KnowledgeConfig.writeToFile(original, configFile)
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertFalse(loaded!!.hybridEnabled)
        assertEquals(30, loaded.hybridRrfK)
        assertEquals(50, loaded.hybridLexicalLimit)
        assertEquals(8.0, loaded.hybridNameWeight)
        assertEquals(2.0, loaded.hybridBodyWeight)
    }

    @Test
    fun `hybrid settings apply defaults when absent from JSON file`() {
        val configFile = File(tempDir, "hybrid-absent.json")
        configFile.writeText("""{ "embeddingProvider": "voyage" }""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertTrue(loaded!!.hybridEnabled)
        assertEquals(60, loaded.hybridRrfK)
    }

    @Test
    fun `configFilePath points to hyindex knowledge directory`() {
        val path = KnowledgeConfig.configFilePath().absolutePath
        assertTrue(
            path.contains(".hyindex") && path.contains("knowledge") && path.endsWith("mcp-config.json"),
            "Config path should be ~/.hyindex/knowledge/mcp-config.json, got: $path"
        )
    }

    @Test
    fun `resolvedIndexPath returns custom path when set`() {
        val config = KnowledgeConfig(indexPath = "/my/custom/path")
        assertEquals(File("/my/custom/path"), config.resolvedIndexPath())
    }

    @Test
    fun `resolvedIndexPath returns default when indexPath is blank`() {
        val config = KnowledgeConfig(indexPath = "")
        val resolved = config.resolvedIndexPath()
        assertTrue(
            resolved.absolutePath.endsWith(".hyindex/knowledge") ||
                resolved.absolutePath.endsWith(".hyindex\\knowledge"),
        )
    }
}
