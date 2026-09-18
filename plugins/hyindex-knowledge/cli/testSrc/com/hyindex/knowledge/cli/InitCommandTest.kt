// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli

import com.hyindex.knowledge.core.config.KnowledgeConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InitCommandTest {

    @Test
    fun `parse defaults are null provider without force`() {
        val args = InitArgs.parse(emptyList())
        assertNull(args.provider)
        assertFalse(args.force)
        assertFalse(args.help)
        assertFalse(args.nonInteractive)
    }

    @Test
    fun `parse accepts voyage provider and force`() {
        val args = InitArgs.parse(listOf("--provider", "voyage", "--force"))
        assertEquals("voyage", args.provider)
        assertTrue(args.force)
    }

    @Test
    fun `parse accepts openai-compatible provider names and secrets`() {
        val args = InitArgs.parse(
            listOf(
                "--provider", "openai",
                "--git-token", "ghp_x",
                "--embedding-url", "https://api.openai.com",
                "--api-key", "sk-test",
                "--non-interactive",
            ),
        )
        assertEquals("openai", args.provider)
        assertEquals("ghp_x", args.gitToken)
        assertEquals("https://api.openai.com", args.embeddingBaseUrl)
        assertEquals("sk-test", args.apiKey)
        assertTrue(args.nonInteractive)
        val custom = InitArgs.parse(listOf("--provider", "litellm"))
        assertEquals("litellm", custom.provider)
    }

    @Test
    fun `parse accepts embedding and reranker profile flags`() {
        val args = InitArgs.parse(
            listOf(
                "--provider", "voyage",
                "--code-model", "voyage-code-3",
                "--text-model", "voyage-4-large",
                "--dimensions", "1024",
                "--reranker-provider", "cohere",
                "--reranker-protocol", "cohere",
                "--reranker-url", "https://rerank.example",
                "--reranker-api-key", "rerank-key",
                "--reranker-model", "rerank-v4.0-pro",
                "--reranker-top-n", "40",
            ),
        )
        assertEquals("voyage", args.provider)
        assertEquals("voyage-code-3", args.codeModel)
        assertEquals("voyage-4-large", args.textModel)
        assertEquals(1024, args.dimensions)
        assertEquals("cohere", args.rerankerProvider)
        assertEquals("cohere", args.rerankerProtocol)
        assertEquals("https://rerank.example", args.rerankerBaseUrl)
        assertEquals("rerank-key", args.rerankerApiKey)
        assertEquals("rerank-v4.0-pro", args.rerankerModel)
        assertEquals(40, args.rerankerTopN)
    }

    @Test
    fun `buildConfig leaves reranker null when model blank`() {
        val cfg = buildConfig(InitAnswers("voyage", null, "", "pa-key"))
        assertNull(cfg.rerankerProfile)
    }

    @Test
    fun `buildConfig creates independent reranker profile when model provided`() {
        val cfg = buildConfig(
            InitAnswers(
                provider = "voyage",
                gitToken = null,
                embeddingBaseUrl = "",
                apiKey = "embedding-key",
                rerankerProvider = "cohere",
                rerankerProtocol = "cohere",
                rerankerBaseUrl = "https://rerank.example",
                rerankerApiKey = "rerank-key",
                rerankerModel = "rerank-v4.0-pro",
                rerankerTopN = 40,
            ),
        )
        val reranker = cfg.rerankerProfile
        assertNotNull(reranker)
        assertEquals("cohere", reranker!!.provider)
        assertEquals("cohere", reranker.protocol)
        assertEquals("https://rerank.example", reranker.baseUrl)
        assertEquals("rerank-key", reranker.apiKey)
        assertEquals("rerank-v4.0-pro", reranker.model)
        assertEquals(40, reranker.topN)
    }

    @Test
    fun `buildConfig openai leaves blanks intact`() {
        val cfg = buildConfig(InitAnswers("openai", null, "", ""))
        assertNull(cfg.gitToken)
        assertEquals("text-embedding-3-large", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.CODE).documentModel)
        assertEquals("text-embedding-3-large", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.DOCS).documentModel)
    }

    @Test
    fun `buildConfig voyage fills voyage defaults`() {
        val cfg = buildConfig(InitAnswers("voyage", "ghp_x", "", "pa-key"))
        assertEquals("ghp_x", cfg.gitToken)
        assertEquals("voyage-code-4", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.CODE).documentModel)
        assertEquals("voyage-4-large", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.DOCS).documentModel)
        assertEquals("voyage-multimodal-3.5", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.VISUAL).documentModel)
    }

    @Test
    fun `buildConfig honors explicit code and text models`() {
        val cfg = buildConfig(
            InitAnswers(
                provider = "voyage",
                gitToken = null,
                embeddingBaseUrl = "http://embeddings.local",
                apiKey = "key",
                codeModel = "voyage-code-3",
                textModel = "voyage-context-3",
                dimensions = 1024,
            ),
        )
        assertEquals("voyage-code-3", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.CODE).documentModel)
        assertEquals("voyage-context-3", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.DOCS).documentModel)
    }

    @Test
    fun `buildConfig cohere fills cohere defaults`() {
        val profile = buildConfig(InitAnswers("cohere", null, "", "co-key"))
            .resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.CODE)
        assertEquals("cohere", profile.provider)
        assertEquals("https://api.cohere.com", profile.baseUrl)
        assertEquals("embed-v4.0", profile.documentModel)
        assertEquals(1024, profile.dimensions)
    }

    @Test
    fun `buildConfig gemini fills native google defaults`() {
        val cfg = buildConfig(InitAnswers("gemini", null, "", "AIza-test"))
        assertEquals("gemini", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.VISUAL).provider)
        assertEquals("gemini-embedding-2", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.VISUAL).documentModel)
    }

    @Test
    fun `buildConfig jina fills jina defaults`() {
        val profile = buildConfig(InitAnswers("jina", null, "", "jina-key"))
            .resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.DOCS)
        assertEquals("jina", profile.provider)
        assertEquals("jina-embeddings-v3", profile.documentModel)
        assertEquals(1024, profile.dimensions)
    }

    @Test
    fun `buildConfig mistral uses corpus-specific defaults`() {
        val cfg = buildConfig(InitAnswers("mistral", null, "", "mistral-key"))
        assertEquals("codestral-embed-2505", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.CODE).documentModel)
        assertEquals("mistral-embed", cfg.resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.DOCS).documentModel)
    }

    @Test
    fun `buildConfig normalizes mixedbread provider`() {
        val profile = buildConfig(InitAnswers("mxbai", null, "", "mx-key"))
            .resolvedEmbeddingProfile(com.hyindex.knowledge.core.db.Corpus.CODE)
        assertEquals("mixedbread", profile.provider)
        assertEquals("mxbai-embed-large", profile.documentModel)
    }

    @Test
    fun `runInit non-interactive writes config under overridden user home`(@TempDir home: File) {
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            runInit(
                listOf(
                    "--provider", "voyage",
                    "--git-token", "ghp_test",
                    "--api-key", "vk_test",
                    "--non-interactive",
                    "--force",
                ),
            )
            val cfg = KnowledgeConfig.loadFromFile()
            assertNotNull(cfg)
            assertNull(cfg!!.rerankerProfile)
            assertTrue(KnowledgeConfig.configFilePath().exists())
            val raw = KnowledgeConfig.configFilePath().readText()
            assertTrue(raw.contains("\"embeddingProfiles\""))
            assertFalse(raw.contains("\"embeddingProvider\""))
        } finally {
            if (previous == null) System.clearProperty("user.home")
            else System.setProperty("user.home", previous)
        }
    }
}
