// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.index

import com.hyindex.knowledge.core.db.EmbeddingCacheDatabase
import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import java.security.MessageDigest


class EmbeddingCacheService(
    private val cache: EmbeddingCacheDatabase,
    private val log: LogProvider = StdoutLogProvider,
) {

    data class CacheLookupResult(

        val cached: Map<Int, FloatArray>,

        val uncachedIndices: List<Int>,
    )


    fun lookup(embeddingTexts: List<String>, modelId: String): CacheLookupResult {
        if (embeddingTexts.isEmpty()) {
            return CacheLookupResult(emptyMap(), emptyList())
        }

        val hashes = embeddingTexts.map { sha256(it) }
        val found = cache.lookup(hashes, modelId)

        val cached = mutableMapOf<Int, FloatArray>()
        val uncached = mutableListOf<Int>()

        for ((idx, hash) in hashes.withIndex()) {
            val vec = found[hash]
            if (vec != null) {
                cached[idx] = vec
            } else {
                uncached.add(idx)
            }
        }

        if (cached.isNotEmpty()) {
            log.info("Embedding cache: ${cached.size} hits, ${uncached.size} misses (${embeddingTexts.size} total)")
        }

        return CacheLookupResult(cached, uncached)
    }


    fun store(embeddingTexts: List<String>, vectors: List<FloatArray>, modelId: String) {
        if (embeddingTexts.isEmpty()) return
        require(embeddingTexts.size == vectors.size) {
            "embeddingTexts.size (${embeddingTexts.size}) != vectors.size (${vectors.size})"
        }

        val entries = embeddingTexts.zip(vectors).map { (text, vec) ->
            EmbeddingCacheDatabase.CacheEntry(
                contentHash = sha256(text),
                modelId = modelId,
                vector = vec,
                dimension = vec.size,
            )
        }
        cache.storeBatch(entries)
    }

    companion object {
        private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        internal fun sha256(text: String): String {
            val md = digest.get()
            md.reset()
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
