// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus

enum class EmbeddingRole { DOCUMENT, QUERY }

interface EmbeddingProvider {
    val modelId: String
    val dimension: Int

    val batchConcurrency: Int get() = 1
    val supportsContextualDocuments: Boolean get() = false
    val supportsMultimodalDocuments: Boolean get() = false

    suspend fun embed(texts: List<String>): List<FloatArray>
    suspend fun embedQuery(query: String): FloatArray
    suspend fun validate()

    suspend fun embedGrouped(documents: List<List<String>>): List<FloatArray> =
        embed(documents.flatten())

    suspend fun embedDocuments(docs: List<MultimodalDocument>): List<FloatArray> =
        embed(docs.map { it.text })

    companion object {
        fun fromConfig(
            config: KnowledgeConfig,
            corpus: Corpus,
            role: EmbeddingRole = EmbeddingRole.DOCUMENT,
        ): EmbeddingProvider {
            val profile = config.resolvedEmbeddingProfile(corpus)
            val model = when (role) {
                EmbeddingRole.DOCUMENT -> profile.documentModel
                EmbeddingRole.QUERY -> profile.queryModel
            }
            val providerName = profile.provider.trim().lowercase()
            requireProviderOwnsModel(providerName, model)
            val effectivePurpose = corpus.embeddingPurpose
            val dimensions = profile.dimensions
            val baseUrl = profile.baseUrl
            val apiKey = profile.apiKey
            val concurrency = profile.concurrency
            return when (providerName) {
                "fake" -> FakeEmbeddingProvider()
                "local" -> LocalEmbeddingProvider()
                "ollama" -> OllamaProvider(
                    baseUrl = baseUrl.ifBlank { "http://localhost:11434" },
                    model = model,
                )
                "voyage" -> VoyageAIProvider(
                    apiKey = apiKey,
                    baseUrl = baseUrl.ifBlank { "https://api.voyageai.com" },
                    model = model,
                    concurrency = concurrency,
                    dimensions = dimensions,
                )
                "cohere" -> CohereEmbeddingProvider(
                    apiKey = apiKey,
                    baseUrl = baseUrl.ifBlank { "https://api.cohere.com" },
                    model = model,
                    concurrency = concurrency,
                )
                "gemini" -> {
                    val base = baseUrl.ifBlank {
                        "https://generativelanguage.googleapis.com/v1beta"
                    }
                    if (base.trimEnd('/').endsWith("/openai")) {
                        OpenAICompatibleProvider(
                            apiKey = apiKey,
                            baseUrl = base,
                            model = model,
                            concurrency = concurrency,
                            dimensions = dimensions,
                        )
                    } else {
                        GeminiEmbeddingProvider(
                            apiKey = apiKey,
                            baseUrl = base,
                            model = model,
                            dimensions = dimensions,
                            concurrency = concurrency,
                            purpose = effectivePurpose,
                        )
                    }
                }
                "jina" -> OpenAICompatibleProvider(
                    apiKey = apiKey,
                    baseUrl = baseUrl.ifBlank { "https://api.jina.ai" },
                    model = model,
                    concurrency = concurrency,
                    dimensions = dimensions,
                    taskForDocuments = "retrieval.passage",
                    taskForQueries = "retrieval.query",
                    lateChunking = true,
                )
                "mistral" -> OpenAICompatibleProvider(
                    apiKey = apiKey,
                    baseUrl = baseUrl.ifBlank { "https://api.mistral.ai" },
                    model = model,
                    concurrency = concurrency,
                    dimensions = dimensions,
                )
                "mixedbread", "mxbai" -> OpenAICompatibleProvider(
                    apiKey = apiKey,
                    baseUrl = baseUrl.ifBlank { "https://api.mixedbread.ai" },
                    model = model,
                    concurrency = concurrency,
                    dimensions = dimensions,
                )
                else -> OpenAICompatibleProvider(
                    apiKey = apiKey,
                    baseUrl = baseUrl.ifBlank { "https://api.openai.com" },
                    model = model,
                    concurrency = concurrency,
                    dimensions = dimensions,
                )
            }
        }

        fun requireCompatibleDimensions(document: EmbeddingProvider, query: EmbeddingProvider) {
            if (document.dimension != query.dimension) {
                throw EmbeddingException.DimensionMismatch(document.dimension, query.dimension)
            }
        }

        fun requireCompatibleModels(documentModel: String, queryModel: String) {
            if (!EmbeddingCompatibility.areCompatible(documentModel, queryModel)) {
                throw EmbeddingException.IncompatibleFamily(documentModel, queryModel)
            }
        }

        fun requireProviderOwnsModel(provider: String, model: String) {
            val p = provider.trim().lowercase()
            val m = model.trim().lowercase()
            if (m.isEmpty()) return
            val geminiModel = isKnownGeminiModel(m)
            val voyageModel = isKnownVoyageModel(m)
            if (p == "voyage" && geminiModel) {
                throw EmbeddingException.ProviderModelMismatch(p, model)
            }
            if (p == "gemini" && voyageModel) {
                throw EmbeddingException.ProviderModelMismatch(p, model)
            }
        }

        private fun isKnownGeminiModel(model: String): Boolean {
            if (model.startsWith("gemini-embedding")) return true
            if (model == "text-embedding-004" || model == "text-embedding-005") return true
            return false
        }

        private fun isKnownVoyageModel(model: String): Boolean =
            model.startsWith("voyage-")
    }
}

sealed class EmbeddingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectionFailed(url: String, cause: Throwable? = null) :
        EmbeddingException("Failed to connect to embedding provider at $url", cause)

    class ModelNotFound(model: String) :
        EmbeddingException("Embedding model '$model' not found. Pull it first with: ollama pull $model")

    class ApiError(status: Int, body: String) :
        EmbeddingException("Embedding API error ($status): $body")

    class InvalidApiKey :
        EmbeddingException("Invalid or missing API key for embedding provider")

    class RateLimited(retryAfterMs: Long?) :
        EmbeddingException("Rate limited${retryAfterMs?.let { ", retry after ${it}ms" } ?: ""}")

    class DimensionMismatch(documentDimension: Int, queryDimension: Int) :
        EmbeddingException("Embedding dimension mismatch: document=$documentDimension query=$queryDimension")

    class IncompatibleFamily(documentModel: String, queryModel: String) :
        EmbeddingException(
            "Embedding models are not in a compatible family: document=$documentModel query=$queryModel",
        )

    class ProviderModelMismatch(provider: String, model: String) :
        EmbeddingException(
            "Embedding model '$model' cannot be used with provider '$provider'; " +
                "do not mix Gemini and Voyage credentials or catalogs",
        )
}

data class MultimodalDocument(
    val text: String,
    val imageBytes: ByteArray,
    val mediaType: String = "image/png",
)
