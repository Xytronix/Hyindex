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

    fun lookupContextual(groups: List<List<String>>, modelId: String): CacheLookupResult {
        if (groups.isEmpty()) return CacheLookupResult(emptyMap(), emptyList())
        val hashesByGroup = groups.map { group ->
            val identity = groupIdentity(group)
            group.indices.map { index -> sha256("$index\n$identity") }
        }
        val found = cache.lookup(hashesByGroup.flatten(), modelId)
        val cached = mutableMapOf<Int, FloatArray>()
        val uncached = mutableListOf<Int>()
        var flatIndex = 0
        for (groupHashes in hashesByGroup) {
            val allHit = groupHashes.all(found::containsKey)
            for (hash in groupHashes) {
                if (allHit) cached[flatIndex] = found.getValue(hash)
                else uncached.add(flatIndex)
                flatIndex++
            }
        }
        if (cached.isNotEmpty()) {
            log.info("Contextual embedding cache: ${cached.size} hits, ${uncached.size} misses")
        }
        return CacheLookupResult(cached, uncached)
    }

    fun storeContextual(groups: List<List<String>>, vectors: List<FloatArray>, modelId: String) {
        if (groups.isEmpty()) return
        val texts = groups.flatten()
        require(texts.size == vectors.size) {
            "grouped texts.size (${texts.size}) != vectors.size (${vectors.size})"
        }
        val entries = mutableListOf<EmbeddingCacheDatabase.CacheEntry>()
        var flatIndex = 0
        for (group in groups) {
            val identity = groupIdentity(group)
            for (index in group.indices) {
                val vector = vectors[flatIndex++]
                entries.add(
                    EmbeddingCacheDatabase.CacheEntry(
                        contentHash = sha256("$index\n$identity"),
                        modelId = modelId,
                        vector = vector,
                        dimension = vector.size,
                    ),
                )
            }
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

        internal fun groupIdentity(group: List<String>): String = group.joinToString("\u001f")
    }
}
