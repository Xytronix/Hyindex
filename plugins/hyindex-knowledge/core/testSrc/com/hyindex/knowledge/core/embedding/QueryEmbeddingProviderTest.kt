// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.config.EmbeddingProfile
import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.index.CorpusIndexManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryEmbeddingProviderTest {
    @Test
    fun `queryModel defaults to documentModel when not specified`() {
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "code" to EmbeddingProfile(provider = "fake", documentModel = "fake-doc"),
            ),
            corpusEmbeddingProfiles = mapOf("code" to "code", "docs" to "code", "gamedata" to "code", "client" to "code"),
        )
        val manager = CorpusIndexManager(config)
        assertSame(manager.getProvider(Corpus.CODE), manager.getQueryProvider(Corpus.CODE))
    }

    @Test
    fun `explicit queryModel creates separate provider`() {
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "code" to EmbeddingProfile(
                    provider = "voyage",
                    apiKey = "test-key",
                    documentModel = "voyage-code-4",
                    queryModel = "voyage-context-4",
                    dimensions = 1024,
                ),
            ),
            corpusEmbeddingProfiles = mapOf("code" to "code", "docs" to "code", "gamedata" to "code", "client" to "code"),
        )
        val manager = CorpusIndexManager(config)
        assertEquals("voyage-code-4", manager.getProvider(Corpus.CODE).modelId)
        assertEquals("voyage-context-4", manager.getQueryProvider(Corpus.CODE).modelId)
        assertNotSame(manager.getProvider(Corpus.CODE), manager.getQueryProvider(Corpus.CODE))
    }

    @Test
    fun `query provider whose dimension differs fails clearly`() {
        val mismatch = assertThrows(EmbeddingException::class.java) {
            EmbeddingProvider.requireCompatibleDimensions(FakeEmbeddingProvider(8), FakeEmbeddingProvider(4))
        }
        assertTrue(mismatch.message!!.contains("dimension", ignoreCase = true))
    }

    @Test
    fun `profile prevents provider model catalog mixing`() {
        val config = KnowledgeConfig(
            embeddingProfiles = mapOf(
                "bad" to EmbeddingProfile(
                    provider = "voyage",
                    documentModel = "gemini-embedding-2",
                    dimensions = 1024,
                ),
            ),
            corpusEmbeddingProfiles = mapOf("docs" to "bad"),
        )
        assertThrows(EmbeddingException.ProviderModelMismatch::class.java) {
            EmbeddingProvider.fromConfig(config, Corpus.DOCS)
        }
    }
}
