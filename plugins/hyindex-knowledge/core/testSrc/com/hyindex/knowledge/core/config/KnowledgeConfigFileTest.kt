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
            embeddingProfiles = mapOf(
                "voyage" to EmbeddingProfile(
                    provider = "voyage",
                    baseUrl = "https://api.voyageai.com",
                    apiKey = "test-api-key",
                    documentModel = "voyage-code-4",
                    dimensions = 1024,
                ),
            ),
            corpusEmbeddingProfiles = mapOf("code" to "voyage", "docs" to "voyage", "gamedata" to "voyage", "client" to "voyage"),
            indexPath = "/custom/index/path",
            resultsPerCorpus = 15,
            maxRelatedConnections = 8,
            indexPatchlines = listOf("release"),
            enabledCorpora = listOf("code", "docs"),
            docsSources = listOf("official", "server"),
            jevRoutingEnabled = true,
            jevApiKey = "jev-test-key",
            jevModel = "jev-latest",
            jevBaseUrl = "https://api.typesafe.ai",
            jevCorpusThreshold = 0.20,
            routedCandidatesPerCorpus = 15,
        )

        val configFile = File(tempDir, "config.json")
        KnowledgeConfig.writeToFile(original, configFile)

        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertEquals(original.embeddingProfiles, loaded!!.embeddingProfiles)
        assertEquals(original.corpusEmbeddingProfiles, loaded.corpusEmbeddingProfiles)
        assertEquals(original.indexPath, loaded.indexPath)
        assertEquals(original.resultsPerCorpus, loaded.resultsPerCorpus)
        assertEquals(original.maxRelatedConnections, loaded.maxRelatedConnections)
        assertEquals(original.indexPatchlines, loaded.indexPatchlines)
        assertEquals(original.enabledCorpora, loaded.enabledCorpora)
        assertEquals(original.docsSources, loaded.docsSources)
        assertEquals(original.jevRoutingEnabled, loaded.jevRoutingEnabled)
        assertEquals(original.jevApiKey, loaded.jevApiKey)
        assertEquals(original.jevModel, loaded.jevModel)
        assertEquals(original.jevBaseUrl, loaded.jevBaseUrl)
        assertEquals(original.jevCorpusThreshold, loaded.jevCorpusThreshold)
        assertEquals(original.routedCandidatesPerCorpus, loaded.routedCandidatesPerCorpus)
    }

    @Test
    fun `indexing selections normalize and reject unknown values`() {
        val config = KnowledgeConfig(
            indexPatchlines = listOf(" Release "),
            enabledCorpora = listOf(" CODE ", "visual", "code"),
            docsSources = listOf(" Official ", "BLOG", "official"),
        )
        assertEquals(setOf("release"), config.resolvedIndexPatchlines())
        assertEquals(setOf("code", "visual"), config.resolvedEnabledCorpora())
        assertEquals(setOf("official", "blog"), config.resolvedDocsSources())
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeConfig(indexPatchlines = listOf("nightly")).resolvedIndexPatchlines()
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeConfig(enabledCorpora = listOf("unknown")).resolvedEnabledCorpora()
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeConfig(docsSources = listOf("official", "unknown")).resolvedDocsSources()
        }
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
        configFile.writeText("""{}""")

        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        val defaults = KnowledgeConfig()
        assertEquals(defaults.embeddingProfiles, loaded!!.embeddingProfiles)
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
        configFile.writeText("""{}""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertNull(loaded!!.gitToken)
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
        configFile.writeText("""{}""")
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
    fun `reranker is null by default`() {
        val config = KnowledgeConfig()
        assertNull(config.rerankerProfile)
    }

    @Test
    fun `reranker settings round-trip through writeToFile and loadFromFile`() {
        val configFile = File(tempDir, "reranker.json")
        val original = KnowledgeConfig(
            embeddingProfiles = mapOf("voyage" to EmbeddingProfile(provider = "voyage", apiKey = "embed-key", documentModel = "voyage-code-4")),
            corpusEmbeddingProfiles = mapOf("code" to "voyage", "docs" to "voyage", "gamedata" to "voyage", "client" to "voyage"),
            rerankerProfile = RerankerProfile(
                provider = "voyage",
                baseUrl = "https://rerank.example",
                apiKey = "rerank-key",
                model = "rerank-2.5",
                topN = 25,
            ),
        )
        KnowledgeConfig.writeToFile(original, configFile)
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertNotNull(loaded!!.rerankerProfile)
        assertEquals("rerank-2.5", loaded.rerankerProfile!!.model)
        assertEquals(25, loaded.rerankerProfile!!.topN)
        assertEquals("https://rerank.example", loaded.rerankerProfile!!.baseUrl)
        assertEquals("rerank-key", loaded.rerankerProfile!!.apiKey)
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
        configFile.writeText("""{}""")
        val loaded = KnowledgeConfig.loadFromFile(configFile)
        assertNotNull(loaded)
        assertTrue(loaded!!.hybridEnabled)
        assertEquals(60, loaded.hybridRrfK)
    }

    @Test
    fun `configFilePath points to hyindex knowledge directory`() {
        val path = KnowledgeConfig.configFilePath().absolutePath
        assertTrue(
            path.contains(".hyindex") && path.contains("knowledge") && path.endsWith("config.json"),
            "Config path should be ~/.hyindex/knowledge/config.json, got: $path"
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
