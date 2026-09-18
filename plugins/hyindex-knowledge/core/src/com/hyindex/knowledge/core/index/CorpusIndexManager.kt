// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.index

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.EmbeddingPurpose
import com.hyindex.knowledge.core.embedding.EmbeddingProvider
import com.hyindex.knowledge.core.embedding.EmbeddingRole
import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import java.nio.file.Path
import java.nio.file.Paths

class CorpusIndexManager(
    private val config: KnowledgeConfig = KnowledgeConfig(),
    private val log: LogProvider = StdoutLogProvider,
) {
    private val indices = mutableMapOf<Corpus, HnswIndex>()
    private val providers = mutableMapOf<Corpus, EmbeddingProvider>()
    private val queryProviders = mutableMapOf<Corpus, EmbeddingProvider>()

    fun getIndex(corpus: Corpus): HnswIndex? {
        indices[corpus]?.let { if (it.isLoaded()) return it }

        val path = hnswPath(corpus)
        if (!path.toFile().exists()) return null

        val provider = getProvider(corpus)
        val index = HnswIndex(provider.dimension, log = log)
        try {
            index.load(path)
            indices[corpus] = index
            return index
        } catch (e: Exception) {
            log.warn("Failed to load HNSW index for ${corpus.displayName}", e)
            return null
        }
    }

    fun getProvider(corpus: Corpus): EmbeddingProvider {
        return providers.getOrPut(corpus) {
            EmbeddingProvider.fromConfig(config, corpus, EmbeddingRole.DOCUMENT)
        }
    }

    fun getQueryProvider(corpus: Corpus): EmbeddingProvider {
        val document = getProvider(corpus)
        val documentModel = config.resolvedDocumentModel(corpus)
        val queryModel = config.resolvedQueryModel(corpus)
        if (queryModel == documentModel) return document
        EmbeddingProvider.requireCompatibleModels(documentModel, queryModel)
        val query = queryProviders.getOrPut(corpus) {
            EmbeddingProvider.fromConfig(config, corpus, EmbeddingRole.QUERY)
        }
        EmbeddingProvider.requireCompatibleDimensions(document, query)
        return query
    }

    fun requireCompatibleQueryDimensions(corpus: Corpus) {
        val document = getProvider(corpus)
        val query = getQueryProvider(corpus)
        EmbeddingProvider.requireCompatibleModels(
            config.resolvedDocumentModel(corpus),
            config.resolvedQueryModel(corpus),
        )
        EmbeddingProvider.requireCompatibleDimensions(document, query)
    }

    fun hnswPath(corpus: Corpus): Path {
        return Paths.get(config.resolvedIndexPath().absolutePath, "hnsw", corpus.hnswFileName)
    }

    fun closeCorpus(corpus: Corpus) {
        indices.remove(corpus)?.close()
        providers.remove(corpus)
        queryProviders.remove(corpus)
    }

    fun closeAll() {
        indices.values.forEach { it.close() }
        indices.clear()
        providers.clear()
        queryProviders.clear()
    }
}
