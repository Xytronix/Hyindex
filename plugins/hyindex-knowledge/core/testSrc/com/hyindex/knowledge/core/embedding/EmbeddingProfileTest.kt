// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.config.EmbeddingProfile
import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.index.VisualMedia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class EmbeddingProfileTest {
    @Test
    fun `different corpora resolve different providers models and dimensions`() {
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "code-voyage" to EmbeddingProfile(
                    provider = "voyage",
                    baseUrl = "https://api.voyageai.com",
                    apiKey = "voyage-key",
                    documentModel = "voyage-code-4",
                    queryModel = "voyage-context-4",
                    dimensions = 1024,
                    concurrency = 2,
                ),
                "docs-gemini" to EmbeddingProfile(
                    provider = "gemini",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                    apiKey = "gemini-key",
                    documentModel = "gemini-embedding-2",
                    dimensions = 768,
                    concurrency = 3,
                ),
            ),
            corpusEmbeddingProfiles = mapOf(
                "code" to "code-voyage",
                "docs" to "docs-gemini",
            ),
        )

        val code = config.resolvedEmbeddingProfile(Corpus.CODE)
        assertEquals("voyage", code.provider)
        assertEquals("voyage-code-4", code.documentModel)
        assertEquals("voyage-context-4", code.queryModel)
        assertEquals(1024, code.dimensions)
        assertEquals(2, code.concurrency)

        val docs = config.resolvedEmbeddingProfile(Corpus.DOCS)
        assertEquals("gemini", docs.provider)
        assertEquals("gemini-embedding-2", docs.documentModel)
        assertEquals("gemini-embedding-2", docs.queryModel)
        assertEquals(768, docs.dimensions)
        assertEquals(3, docs.concurrency)

        val codeProvider = EmbeddingProvider.fromConfig(config, corpus = Corpus.CODE)
        val docsProvider = EmbeddingProvider.fromConfig(config, corpus = Corpus.DOCS)
        assertEquals("voyage-code-4", codeProvider.modelId)
        assertEquals("gemini-embedding-2", docsProvider.modelId)
        assertEquals(1024, codeProvider.dimension)
        assertEquals(768, docsProvider.dimension)
    }

    @Test
    fun `query provider uses profile query model and reuses identical document model`() {
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "code" to EmbeddingProfile("voyage", documentModel = "voyage-code-4", queryModel = "voyage-context-4", dimensions = 1024),
                "text" to EmbeddingProfile("gemini", documentModel = "gemini-embedding-2", dimensions = 1024),
            ),
            corpusEmbeddingProfiles = mapOf("code" to "code", "docs" to "text"),
        )
        val manager = com.hyindex.knowledge.core.index.CorpusIndexManager(config)
        assertEquals("voyage-context-4", manager.getQueryProvider(Corpus.CODE).modelId)
        assertNotSame(manager.getProvider(Corpus.CODE), manager.getQueryProvider(Corpus.CODE))
        assertSame(manager.getProvider(Corpus.DOCS), manager.getQueryProvider(Corpus.DOCS))
    }

    @Test
    fun `profiles select every supported provider adapter`() {
        val cases = listOf(
            Triple("openai", "text-embedding-3-small", "OpenAICompatibleProvider"),
            Triple("custom", "BAAI/bge-m3", "OpenAICompatibleProvider"),
            Triple("voyage", "voyage-4-large", "VoyageAIProvider"),
            Triple("gemini", "gemini-embedding-2", "GeminiEmbeddingProvider"),
            Triple("cohere", "embed-v4.0", "CohereEmbeddingProvider"),
            Triple("jina", "jina-embeddings-v3", "OpenAICompatibleProvider"),
            Triple("mistral", "mistral-embed", "OpenAICompatibleProvider"),
            Triple("mixedbread", "mxbai-embed-large", "OpenAICompatibleProvider"),
            Triple("ollama", "nomic-embed-text-v2-moe", "OllamaProvider"),
            Triple("fake", "test", "FakeEmbeddingProvider"),
        )
        for ((providerName, model, expectedClass) in cases) {
            val config = KnowledgeConfig(
                embeddingProfiles = mapOf(
                    "docs" to EmbeddingProfile(providerName, documentModel = model),
                ),
                corpusEmbeddingProfiles = mapOf("docs" to "docs"),
            )
            assertEquals(
                expectedClass,
                EmbeddingProvider.fromConfig(config, corpus = Corpus.DOCS).javaClass.simpleName,
                providerName,
            )
        }
    }

    @Test
    fun `openai profile accepts newer compatible model ids with explicit dimensions`() {
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "future" to EmbeddingProfile(
                    provider = "openai",
                    documentModel = "text-embedding-future",
                    dimensions = 2048,
                ),
            ),
            corpusEmbeddingProfiles = mapOf("docs" to "future"),
        )

        val provider = EmbeddingProvider.fromConfig(config, Corpus.DOCS)

        assertEquals("text-embedding-future", provider.modelId)
        assertEquals(2048, provider.dimension)
    }

    @Test
    fun `missing named profile fails clearly`() {
        val config = KnowledgeConfig(corpusEmbeddingProfiles = mapOf("code" to "missing"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            config.resolvedEmbeddingProfile(Corpus.CODE)
        }
        assertTrue(error.message!!.contains("missing"))
    }


    @Test
    fun `visual media protocol follows the visual corpus profile`() {
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "gemini-visual" to EmbeddingProfile(
                    provider = "gemini",
                    documentModel = "gemini-embedding-2",
                    dimensions = 1024,
                ),
            ),
            corpusEmbeddingProfiles = mapOf("visual" to "gemini-visual"),
        )
        assertTrue(VisualMedia.usesGemini(config))
        assertTrue("pdf" in VisualMedia.acceptedExtensions(config))
        assertTrue("webp" !in VisualMedia.acceptedExtensions(config))
    }

    @Test
    fun `profiles round trip through config file`() {
        val file = Files.createTempFile("hyindex-profiles", ".json").toFile()
        val original = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "visual" to EmbeddingProfile(
                    provider = "gemini",
                    baseUrl = "https://example.test",
                    apiKey = "key",
                    documentModel = "gemini-embedding-2",
                    dimensions = 1024,
                ),
            ),
            corpusEmbeddingProfiles = mapOf("visual" to "visual"),
        )
        KnowledgeConfig.writeToFile(original, file)
        val loaded = KnowledgeConfig.loadFromFile(file)!!
        assertEquals(original.embeddingProfiles, loaded.embeddingProfiles)
        assertEquals(original.corpusEmbeddingProfiles, loaded.corpusEmbeddingProfiles)
    }
}
