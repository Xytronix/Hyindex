// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.EmbeddingPurpose
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

class LocalEmbeddingProviderTest {

    @Test
    fun `local provider reports missing plugin when onnx jar absent`() {
        if (LocalEmbeddingProvider.pluginAvailable()) return
        val provider = EmbeddingProvider.fromConfig(KnowledgeConfig(embeddingProvider = "local"), EmbeddingPurpose.CODE)
        assertTrue(provider is LocalEmbeddingProvider)
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { provider.validate() }
        }
        assertTrue(ex.message!!.contains("hyindex-embeddings-local.jar"), ex.message)
    }

    @Test
    @EnabledIf("pluginPresent")
    fun `local provider embeds when plugin present`() = runBlocking {
        val provider = LocalEmbeddingProvider()
        val vectors = provider.embed(listOf("hello world", "hyindex local embeddings"))
        assertEquals(2, vectors.size)
        assertEquals(384, vectors[0].size)
        assertEquals(384, provider.embedQuery("query").size)
    }

    companion object {
        @JvmStatic
        fun pluginPresent(): Boolean = LocalEmbeddingProvider.pluginAvailable()
    }
}
