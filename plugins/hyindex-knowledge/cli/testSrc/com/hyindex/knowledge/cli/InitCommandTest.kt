// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.cli

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.source.GitSourceProvider
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
    fun `parse accepts model and dimensions flags`() {
        val args = InitArgs.parse(
            listOf(
                "--provider", "voyage",
                "--code-model", "voyage-code-3",
                "--text-model", "voyage-4-large",
                "--dimensions", "1024",
                "--reranker-model", "rerank-2.5",
                "--reranker-top-n", "40",
            ),
        )
        assertEquals("voyage", args.provider)
        assertEquals("voyage-code-3", args.codeModel)
        assertEquals("voyage-4-large", args.textModel)
        assertEquals(1024, args.dimensions)
        assertEquals("rerank-2.5", args.rerankerModel)
        assertEquals(40, args.rerankerTopN)
    }

    @Test
    fun `buildConfig leaves reranker disabled when model blank`() {
        val cfg = buildConfig(InitAnswers("voyage", null, "", "pa-key"))
        assertFalse(cfg.rerankerEnabled)
        assertEquals("rerank-2.5", cfg.rerankerModel)
    }

    @Test
    fun `buildConfig enables reranker when model provided`() {
        val cfg = buildConfig(
            InitAnswers(
                provider = "voyage",
                gitToken = null,
                embeddingBaseUrl = "",
                apiKey = "pa-key",
                rerankerModel = "rerank-2.5",
                rerankerTopN = 40,
            ),
        )
        assertTrue(cfg.rerankerEnabled)
        assertEquals("rerank-2.5", cfg.rerankerModel)
        assertEquals(40, cfg.rerankerTopN)
    }

    @Test
    fun `buildConfig openai leaves blanks intact`() {
        val cfg = buildConfig(InitAnswers("openai", null, "", ""))
        assertEquals("openai", cfg.embeddingProvider)
        assertEquals("", cfg.embeddingBaseUrl)
        assertEquals("", cfg.embeddingApiKey)
        assertNull(cfg.gitToken)
        assertEquals(GitSourceProvider.DEFAULT_REPO_URL, cfg.gitRepoUrl)
        assertEquals("text-embedding-3-large", cfg.embeddingCodeModel)
        assertEquals("text-embedding-3-large", cfg.embeddingTextModel)
    }

    @Test
    fun `buildConfig voyage fills voyage defaults`() {
        val cfg = buildConfig(InitAnswers("voyage", "ghp_x", "", "pa-key"))
        assertEquals("voyage", cfg.embeddingProvider)
        assertEquals("https://api.voyageai.com", cfg.embeddingBaseUrl)
        assertEquals("pa-key", cfg.embeddingApiKey)
        assertEquals("ghp_x", cfg.gitToken)
        assertEquals("voyage-code-3", cfg.embeddingCodeModel)
        assertEquals("voyage-4-large", cfg.embeddingTextModel)
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
        assertEquals("http://embeddings.local", cfg.embeddingBaseUrl)
        assertEquals("voyage-code-3", cfg.embeddingCodeModel)
        assertEquals("voyage-context-3", cfg.embeddingTextModel)
        assertEquals(1024, cfg.embeddingDimensions)
    }

    @Test
    fun `buildConfig cohere fills cohere defaults`() {
        val cfg = buildConfig(InitAnswers("cohere", null, "", "co-key"))
        assertEquals("cohere", cfg.embeddingProvider)
        assertEquals("https://api.cohere.com", cfg.embeddingBaseUrl)
        assertEquals("co-key", cfg.embeddingApiKey)
        assertEquals("embed-v4.0", cfg.embeddingCodeModel)
        assertEquals("embed-v4.0", cfg.embeddingTextModel)
    }

    @Test
    fun `buildConfig gemini fills native google defaults`() {
        val cfg = buildConfig(InitAnswers("gemini", null, "", "AIza-test"))
        assertEquals("gemini", cfg.embeddingProvider)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", cfg.embeddingBaseUrl)
        assertEquals("AIza-test", cfg.embeddingApiKey)
        assertEquals("gemini-embedding-001", cfg.embeddingCodeModel)
        assertEquals("gemini-embedding-001", cfg.embeddingTextModel)
    }

    @Test
    fun `buildConfig jina fills jina defaults`() {
        val cfg = buildConfig(InitAnswers("jina", null, "", "jina-key"))
        assertEquals("jina", cfg.embeddingProvider)
        assertEquals("https://api.jina.ai", cfg.embeddingBaseUrl)
        assertEquals("jina-embeddings-v3", cfg.embeddingCodeModel)
        assertEquals("jina-embeddings-v3", cfg.embeddingTextModel)
        assertEquals(1024, cfg.embeddingDimensions)
    }

    @Test
    fun `buildConfig mistral fills mistral defaults`() {
        val cfg = buildConfig(InitAnswers("mistral", null, "", "mistral-key"))
        assertEquals("mistral", cfg.embeddingProvider)
        assertEquals("https://api.mistral.ai", cfg.embeddingBaseUrl)
        assertEquals("codestral-embed-2505", cfg.embeddingCodeModel)
        assertEquals("mistral-embed", cfg.embeddingTextModel)
    }

    @Test
    fun `buildConfig mixedbread fills mixedbread defaults`() {
        val cfg = buildConfig(InitAnswers("mxbai", null, "", "mx-key"))
        assertEquals("mixedbread", cfg.embeddingProvider)
        assertEquals("https://api.mixedbread.ai", cfg.embeddingBaseUrl)
        assertEquals("mxbai-embed-large", cfg.embeddingCodeModel)
        assertEquals("mxbai-embed-large", cfg.embeddingTextModel)
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
            assertEquals("voyage", cfg!!.embeddingProvider)
            assertEquals("ghp_test", cfg.gitToken)
            assertEquals("vk_test", cfg.embeddingApiKey)
            assertEquals(GitSourceProvider.DEFAULT_REPO_URL, cfg.gitRepoUrl)
            assertFalse(cfg.rerankerEnabled)
            assertTrue(KnowledgeConfig.configFilePath().exists())
            val raw = KnowledgeConfig.configFilePath().readText()
            assertTrue("embeddingApiKey" in raw)
            assertFalse("voyageApiKey" in raw)
        } finally {
            if (previous == null) System.clearProperty("user.home")
            else System.setProperty("user.home", previous)
        }
    }
}
