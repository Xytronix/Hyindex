package com.hyindex.knowledge.core.db

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.config.EmbeddingProfile
import com.hyindex.knowledge.core.embedding.EmbeddingProvider
import com.hyindex.knowledge.core.index.CorpusIndexManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class VisualCorpusTest {

    @Test
    fun `visual is an isolated corpus with its own hnsw filename and image purpose`() {
        assertEquals("visual", Corpus.VISUAL.id)
        assertEquals("visual.hnsw", Corpus.VISUAL.hnswFileName)
        assertEquals(EmbeddingPurpose.IMAGE, Corpus.VISUAL.embeddingPurpose)
        assertNotEquals(Corpus.CODE.hnswFileName, Corpus.VISUAL.hnswFileName)
        assertNotEquals(Corpus.DOCS.hnswFileName, Corpus.VISUAL.hnswFileName)
        assertNotEquals(Corpus.CLIENT.hnswFileName, Corpus.VISUAL.hnswFileName)
        assertNotEquals(Corpus.GAMEDATA.hnswFileName, Corpus.VISUAL.hnswFileName)
    }

    @Test
    fun `default search and default indexer corpora exclude visual`() {
        val defaultSearch = Corpus.SEARCH_DEFAULT.map { it.id }.toSet()
        assertEquals(setOf("code", "client", "gamedata", "docs"), defaultSearch)
        assertFalse(Corpus.VISUAL in Corpus.SEARCH_DEFAULT)
    }

    @Test
    fun `visual raster root is opt-in and blank by default`() {
        assertEquals("", KnowledgeConfig().visualRasterRoot)
    }

    @Test
    fun `config file loads visual raster root and image model without changing text models`() {
        val dir = Files.createTempDirectory("hyindex-visual-cfg").toFile()
        val file = File(dir, "mcp-config.json")
        file.writeText(
            """
            {
              "embeddingProfiles": {
                "code": {"provider": "voyage", "documentModel": "voyage-code-4"},
                "text": {"provider": "voyage", "documentModel": "voyage-4-large"},
                "visual": {"provider": "gemini", "documentModel": "gemini-embedding-2"}
              },
              "corpusEmbeddingProfiles": {
                "code": "code",
                "docs": "text",
                "gamedata": "text",
                "client": "text",
                "visual": "visual"
              },
              "visualRasterRoot": "/opt/hytale-assets/rasters"
            }
            """.trimIndent(),
        )

        val loaded = KnowledgeConfig.loadFromFile(file)
        assertNotNull(loaded)
        assertEquals("/opt/hytale-assets/rasters", loaded!!.visualRasterRoot)
        assertEquals("gemini-embedding-2", loaded.resolvedEmbeddingProfile(Corpus.VISUAL).documentModel)
        assertEquals("voyage-code-4", loaded.resolvedEmbeddingProfile(Corpus.CODE).documentModel)
        assertEquals("voyage-4-large", loaded.resolvedEmbeddingProfile(Corpus.DOCS).documentModel)
    }

    @Test
    fun `corpus index manager points visual at visual hnsw and an image provider`() {
        val base = Files.createTempDirectory("hyindex-visual-mgr").toFile()
        val cfg = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "code" to EmbeddingProfile(provider = "voyage", apiKey = "vk-test", documentModel = "voyage-code-4"),
                "text" to EmbeddingProfile(provider = "voyage", apiKey = "vk-test", documentModel = "voyage-4-large"),
                "visual" to EmbeddingProfile(provider = "gemini", apiKey = "vk-test", documentModel = "gemini-embedding-2"),
            ),
            corpusEmbeddingProfiles = mapOf("code" to "code", "docs" to "text", "gamedata" to "text", "client" to "text", "visual" to "visual"),
            indexPath = base.absolutePath,
            activeVersion = "release_test",
            visualRasterRoot = File(base, "rasters").absolutePath,
        )
        val mgr = CorpusIndexManager(cfg)
        val visualPath = mgr.hnswPath(Corpus.VISUAL).toFile()
        assertEquals("visual.hnsw", visualPath.name)
        assertTrue(visualPath.parentFile.name == "hnsw")
        assertEquals("gemini-embedding-2", mgr.getProvider(Corpus.VISUAL).modelId)
        assertEquals("voyage-code-4", mgr.getProvider(Corpus.CODE).modelId)
        assertEquals("voyage-4-large", mgr.getProvider(Corpus.DOCS).modelId)
        mgr.closeAll()
    }

    @Test
    fun `fromConfig resolves different models per corpus`() {
        val cfg = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "code" to EmbeddingProfile(provider = "voyage", apiKey = "vk-test", documentModel = "voyage-code-4"),
                "text" to EmbeddingProfile(provider = "voyage", apiKey = "vk-test", documentModel = "voyage-4-large"),
                "visual" to EmbeddingProfile(provider = "gemini", apiKey = "vk-test", documentModel = "gemini-embedding-2"),
            ),
            corpusEmbeddingProfiles = mapOf("code" to "code", "docs" to "text", "gamedata" to "text", "client" to "text", "visual" to "visual"),
        )
        val visual = EmbeddingProvider.fromConfig(cfg, Corpus.VISUAL)
        val code = EmbeddingProvider.fromConfig(cfg, Corpus.CODE)
        val docs = EmbeddingProvider.fromConfig(cfg, Corpus.DOCS)
        assertEquals("gemini-embedding-2", visual.modelId)
        assertEquals("voyage-code-4", code.modelId)
        assertEquals("voyage-4-large", docs.modelId)
    }
}
