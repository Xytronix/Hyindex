// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.index

import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.embedding.EmbeddingProvider
import kotlinx.coroutines.runBlocking

data class SourceChunk(
    val corpus: Corpus,
    val text: String,
    val owningFile: String? = null,
    val relativePath: String? = null,
    val filePath: String? = null,
)

object ContextualDocumentGroups {
    fun keyFor(chunk: SourceChunk): String {
        val key = when (chunk.corpus) {
            Corpus.CODE -> chunk.owningFile
            Corpus.DOCS, Corpus.CLIENT -> chunk.relativePath
            Corpus.GAMEDATA, Corpus.VISUAL -> chunk.filePath
        }
        return "${chunk.corpus.id}\u0000${key.orEmpty()}"
    }

    fun group(chunks: List<SourceChunk>): List<List<SourceChunk>> {
        if (chunks.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<SourceChunk>>()
        for (chunk in chunks) grouped.getOrPut(keyFor(chunk)) { mutableListOf() }.add(chunk)
        return grouped.values.map { it.toList() }
    }

    fun embedInOrder(
        chunks: List<SourceChunk>,
        provider: EmbeddingProvider,
        cache: EmbeddingCacheService,
    ): List<FloatArray> {
        if (chunks.isEmpty()) return emptyList()
        if (!provider.supportsContextualDocuments) {
            val texts = chunks.map(SourceChunk::text)
            val cached = cache.lookup(texts, provider.modelId)
            val vectors = arrayOfNulls<FloatArray>(chunks.size)
            cached.cached.forEach { (index, vector) -> vectors[index] = vector }
            for (indices in cached.uncachedIndices.chunked(NON_CONTEXTUAL_CHECKPOINT_SIZE)) {
                val missingTexts = indices.map(texts::get)
                val embedded = runBlocking { provider.embed(missingTexts) }
                cache.store(missingTexts, embedded, provider.modelId)
                indices.forEachIndexed { index, originalIndex -> vectors[originalIndex] = embedded[index] }
            }
            return vectors.map { checkNotNull(it) }
        }

        val grouped = LinkedHashMap<String, MutableList<IndexedValue<SourceChunk>>>()
        chunks.withIndex().forEach { indexed ->
            grouped.getOrPut(keyFor(indexed.value)) { mutableListOf() }.add(indexed)
        }
        val indexedGroups = grouped.values.map { it.toList() }
        val textGroups = indexedGroups.map { group -> group.map { it.value.text } }
        val flattenedChunks = indexedGroups.flatten()
        val cacheResult = cache.lookupContextual(textGroups, provider.modelId)
        val uncachedIndices = cacheResult.uncachedIndices.toHashSet()
        val vectors = arrayOfNulls<FloatArray>(chunks.size)
        cacheResult.cached.forEach { (flatIndex, vector) ->
            vectors[flattenedChunks[flatIndex].index] = vector
        }

        val uncachedGroupIndices = mutableListOf<Int>()
        var flatOffset = 0
        textGroups.forEachIndexed { groupIndex, texts ->
            if (texts.indices.any { flatOffset + it in uncachedIndices }) uncachedGroupIndices.add(groupIndex)
            flatOffset += texts.size
        }
        if (uncachedGroupIndices.isNotEmpty()) {
            val uncachedGroups = uncachedGroupIndices.map(textGroups::get)
            val embedded = runBlocking { provider.embedGrouped(uncachedGroups) }
            cache.storeContextual(uncachedGroups, embedded, provider.modelId)
            var embeddedIndex = 0
            for (groupIndex in uncachedGroupIndices) {
                for (chunk in indexedGroups[groupIndex]) vectors[chunk.index] = embedded[embeddedIndex++]
            }
        }
        return vectors.map { checkNotNull(it) }
    }

    private const val NON_CONTEXTUAL_CHECKPOINT_SIZE = 100
}
