// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.embedding

import com.hyve.knowledge.core.config.KnowledgeConfig
import com.hyve.knowledge.core.db.EmbeddingPurpose

interface EmbeddingProvider {
    val modelId: String
    val dimension: Int

    val batchConcurrency: Int get() = 1

    suspend fun embed(texts: List<String>): List<FloatArray>
    suspend fun embedQuery(query: String): FloatArray
    suspend fun validate()

    companion object {
        fun fromConfig(config: KnowledgeConfig, purpose: EmbeddingPurpose = EmbeddingPurpose.CODE): EmbeddingProvider {
            val model = when (purpose) {
                EmbeddingPurpose.CODE -> config.embeddingCodeModel
                EmbeddingPurpose.TEXT -> config.embeddingTextModel
            }
            return when (val name = config.embeddingProvider.lowercase()) {
                "fake" -> FakeEmbeddingProvider()
                "local" -> LocalEmbeddingProvider()
                "ollama" -> OllamaProvider(
                    baseUrl = config.embeddingBaseUrl.ifBlank { "http://localhost:11434" },
                    model = model,
                )
                "voyage" -> VoyageAIProvider(
                    apiKey = config.embeddingApiKey,
                    baseUrl = config.embeddingBaseUrl.ifBlank { "https://api.voyageai.com" },
                    model = model,
                    concurrency = config.embeddingConcurrency,
                    dimensions = config.embeddingDimensions,
                )
                "cohere" -> CohereEmbeddingProvider(
                    apiKey = config.embeddingApiKey,
                    baseUrl = config.embeddingBaseUrl.ifBlank { "https://api.cohere.com" },
                    model = model,
                    concurrency = config.embeddingConcurrency,
                )
                "gemini" -> {
                    val base = config.embeddingBaseUrl.ifBlank {
                        "https://generativelanguage.googleapis.com/v1beta"
                    }
                    if (base.trimEnd('/').endsWith("/openai")) {
                        OpenAICompatibleProvider(
                            apiKey = config.embeddingApiKey,
                            baseUrl = base,
                            model = model,
                            concurrency = config.embeddingConcurrency,
                            dimensions = config.embeddingDimensions,
                        )
                    } else {
                        GeminiEmbeddingProvider(
                            apiKey = config.embeddingApiKey,
                            baseUrl = base,
                            model = model,
                            dimensions = config.embeddingDimensions,
                            concurrency = config.embeddingConcurrency,
                        )
                    }
                }
                "jina" -> OpenAICompatibleProvider(
                    apiKey = config.embeddingApiKey,
                    baseUrl = config.embeddingBaseUrl.ifBlank { "https://api.jina.ai" },
                    model = model,
                    concurrency = config.embeddingConcurrency,
                    dimensions = config.embeddingDimensions,
                    taskForDocuments = "retrieval.passage",
                    taskForQueries = "retrieval.query",
                    lateChunking = true,
                )
                "mistral" -> OpenAICompatibleProvider(
                    apiKey = config.embeddingApiKey,
                    baseUrl = config.embeddingBaseUrl.ifBlank { "https://api.mistral.ai" },
                    model = model,
                    concurrency = config.embeddingConcurrency,
                    dimensions = config.embeddingDimensions,
                )
                "mixedbread", "mxbai" -> OpenAICompatibleProvider(
                    apiKey = config.embeddingApiKey,
                    baseUrl = config.embeddingBaseUrl.ifBlank { "https://api.mixedbread.ai" },
                    model = model,
                    concurrency = config.embeddingConcurrency,
                    dimensions = config.embeddingDimensions,
                )
                // openai / any other name (OpenAI-compatible /v1/embeddings):
                // OpenAI-compatible POST {base}/v1/embeddings
                else -> OpenAICompatibleProvider(
                    apiKey = config.embeddingApiKey,
                    baseUrl = config.embeddingBaseUrl.ifBlank { "https://api.openai.com" },
                    model = model,
                    concurrency = config.embeddingConcurrency,
                    dimensions = config.embeddingDimensions,
                )
            }
        }
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
}
