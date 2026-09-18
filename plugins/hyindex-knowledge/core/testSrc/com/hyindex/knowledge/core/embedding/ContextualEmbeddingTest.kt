// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.index.ContextualDocumentGroups
import com.hyindex.knowledge.core.index.EmbeddingCacheService
import com.hyindex.knowledge.core.index.SourceChunk
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ContextualEmbeddingTest {

    private class RecordingGroupedProvider(
        private val dim: Int = 4,
    ) : EmbeddingProvider {
        override val modelId: String = "recording-context"
        override val dimension: Int = dim
        override val supportsContextualDocuments: Boolean = true
        val receivedDocuments = mutableListOf<List<String>>()

        override suspend fun validate() {}
        override suspend fun embedQuery(query: String): FloatArray = FloatArray(dim)
        override suspend fun embed(texts: List<String>): List<FloatArray> {
            throw AssertionError("Context 4 must send grouped documents, not flattened embed()")
        }

        override suspend fun embedGrouped(documents: List<List<String>>): List<FloatArray> {
            receivedDocuments += documents
            var ordinal = 0
            return documents.flatMap { doc ->
                doc.map { _ ->
                    FloatArray(dim).also { it[0] = ordinal.toFloat() }.also { ordinal++ }
                }
            }
        }
    }

    private class RecordingFlatProvider : EmbeddingProvider {
        override val modelId: String = "recording-flat"
        override val dimension: Int = 2
        val batches = mutableListOf<List<String>>()

        override suspend fun validate() {}
        override suspend fun embedQuery(query: String): FloatArray = FloatArray(dimension)
        override suspend fun embed(texts: List<String>): List<FloatArray> {
            batches += texts
            return texts.mapIndexed { index, _ -> floatArrayOf(index.toFloat(), 0f) }
        }
    }

    private class CheckpointThenFailProvider(
        private var remainingSuccesses: Int,
    ) : EmbeddingProvider {
        override val modelId: String = "checkpoint-flat"
        override val dimension: Int = 2
        val batches = mutableListOf<List<String>>()

        override suspend fun validate() {}
        override suspend fun embedQuery(query: String): FloatArray = FloatArray(dimension)
        override suspend fun embed(texts: List<String>): List<FloatArray> {
            if (remainingSuccesses <= 0) {
                throw RuntimeException("rate limit after checkpoint")
            }
            remainingSuccesses--
            batches += texts
            return texts.map { text ->
                floatArrayOf(text.removePrefix("chunk-").toFloat(), 1f)
            }
        }
    }

    @Test
    fun `embedGrouped sends one document per source and returns vectors in original chunk order`() = runBlocking {
        val provider = RecordingGroupedProvider()
        val documents = listOf(
            listOf("Foo#a", "Foo#b"),
            listOf("Bar#c"),
        )
        val vectors = provider.embedGrouped(documents)
        assertEquals(listOf(listOf("Foo#a", "Foo#b"), listOf("Bar#c")), provider.receivedDocuments)
        assertEquals(3, vectors.size)
        assertEquals(0f, vectors[0][0])
        assertEquals(1f, vectors[1][0])
        assertEquals(2f, vectors[2][0])
    }

    @Test
    fun `grouping keys use owning file, docs path, UI path, and gamedata file path`() {
        val chunks = listOf(
            SourceChunk(Corpus.CODE, "m1", owningFile = "com/example/Foo.java"),
            SourceChunk(Corpus.CODE, "m2", owningFile = "com/example/Foo.java"),
            SourceChunk(Corpus.CODE, "m3", owningFile = "com/example/Bar.java"),
            SourceChunk(Corpus.DOCS, "s1", relativePath = "guides/modding.md"),
            SourceChunk(Corpus.DOCS, "s2", relativePath = "guides/modding.md"),
            SourceChunk(Corpus.DOCS, "s3", relativePath = "guides/other.md"),
            SourceChunk(Corpus.CLIENT, "u1", relativePath = "Shared/UI/Inventory.xaml"),
            SourceChunk(Corpus.CLIENT, "u2", relativePath = "Shared/UI/Inventory.xaml"),
            SourceChunk(Corpus.CLIENT, "u3", relativePath = "Shared/UI/Hud.xaml"),
            SourceChunk(Corpus.GAMEDATA, "g1", filePath = "Server/Item/Sword.json"),
            SourceChunk(Corpus.GAMEDATA, "g2", filePath = "Server/Item/Sword.json"),
            SourceChunk(Corpus.GAMEDATA, "g3", filePath = "Server/Item/Axe.json"),
        )
        val groups = ContextualDocumentGroups.group(chunks)
        assertEquals(8, groups.size, "four corpora each split into two source documents; got ${groups.map { it.map(SourceChunk::text) }}")
        assertEquals(listOf("m1", "m2"), groups[0].map(SourceChunk::text))
        assertEquals(listOf("m3"), groups[1].map(SourceChunk::text))
        assertEquals(listOf("s1", "s2"), groups[2].map(SourceChunk::text))
        assertEquals(listOf("s3"), groups[3].map(SourceChunk::text))
        assertEquals(listOf("u1", "u2"), groups[4].map(SourceChunk::text))
        assertEquals(listOf("u3"), groups[5].map(SourceChunk::text))
        assertEquals(listOf("g1", "g2"), groups[6].map(SourceChunk::text))
        assertEquals(listOf("g3"), groups[7].map(SourceChunk::text))

        assertEquals(
            ContextualDocumentGroups.keyFor(chunks[0]),
            ContextualDocumentGroups.keyFor(chunks[1]),
        )
        assertTrue(ContextualDocumentGroups.keyFor(chunks[0]) != ContextualDocumentGroups.keyFor(chunks[2]))
        assertEquals(
            ContextualDocumentGroups.keyFor(chunks[3]),
            ContextualDocumentGroups.keyFor(chunks[4]),
        )
        assertEquals(
            ContextualDocumentGroups.keyFor(chunks[6]),
            ContextualDocumentGroups.keyFor(chunks[7]),
        )
        assertEquals(
            ContextualDocumentGroups.keyFor(chunks[9]),
            ContextualDocumentGroups.keyFor(chunks[10]),
        )
    }

    @Test
    fun `changing one sibling invalidates the whole contextual cache group`() {
        val dir = Files.createTempDirectory("hyindex-ctx-cache").toFile()
        val cache = EmbeddingCacheService(
            EmbeddingCacheDatabase.forFile(java.io.File(dir, "embedding-cache.db"), StdoutLogProvider),
            StdoutLogProvider,
        )
        val group = listOf("chunk-a", "chunk-b")
        val vectors = listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        cache.storeContextual(listOf(group), vectors, "voyage-context-4")

        val hit = cache.lookupContextual(listOf(group), "voyage-context-4")
        assertEquals(2, hit.cached.size)
        assertTrue(hit.uncachedIndices.isEmpty())

        val mutated = listOf("chunk-a", "chunk-b-changed")
        val miss = cache.lookupContextual(listOf(mutated), "voyage-context-4")
        assertTrue(miss.cached.isEmpty(), "sibling change must miss the whole group; cached=${miss.cached.keys}")
        assertEquals(listOf(0, 1), miss.uncachedIndices)
    }

    @Test
    fun `non contextual providers retain per chunk cache reuse`() {
        val dir = Files.createTempDirectory("hyindex-flat-cache").toFile()
        val cache = EmbeddingCacheService(
            EmbeddingCacheDatabase.forFile(java.io.File(dir, "embedding-cache.db"), StdoutLogProvider),
            StdoutLogProvider,
        )
        val provider = RecordingFlatProvider()
        val original = listOf(
            SourceChunk(Corpus.CODE, "chunk-a", owningFile = "Shared.java"),
            SourceChunk(Corpus.CODE, "chunk-b", owningFile = "Shared.java"),
        )
        ContextualDocumentGroups.embedInOrder(original, provider, cache)
        ContextualDocumentGroups.embedInOrder(
            listOf(original[0], original[1].copy(text = "chunk-b-changed")),
            provider,
            cache,
        )

        assertEquals(listOf(listOf("chunk-a", "chunk-b"), listOf("chunk-b-changed")), provider.batches)
    }

    @Test
    fun `non contextual re-embed resumes from persisted 100-text checkpoints`() {
        val dir = Files.createTempDirectory("hyindex-checkpoint-cache").toFile()
        val cache = EmbeddingCacheService(
            EmbeddingCacheDatabase.forFile(java.io.File(dir, "embedding-cache.db"), StdoutLogProvider),
            StdoutLogProvider,
        )
        val chunks = (0 until 150).map { index ->
            SourceChunk(Corpus.CODE, "chunk-$index", owningFile = "File$index.java")
        }
        val failing = CheckpointThenFailProvider(remainingSuccesses = 1)
        val error = assertThrows(RuntimeException::class.java) {
            ContextualDocumentGroups.embedInOrder(chunks, failing, cache)
        }
        assertEquals("rate limit after checkpoint", error.message)
        assertEquals(listOf(chunks.take(100).map(SourceChunk::text)), failing.batches)

        val resume = CheckpointThenFailProvider(remainingSuccesses = 1)
        val vectors = ContextualDocumentGroups.embedInOrder(chunks, resume, cache)
        assertEquals(listOf(chunks.drop(100).map(SourceChunk::text)), resume.batches)
        assertEquals(150, vectors.size)
        chunks.forEachIndexed { index, _ ->
            assertEquals(index.toFloat(), vectors[index][0], "vector order must match original chunk $index")
            assertEquals(1f, vectors[index][1])
        }
    }
}
