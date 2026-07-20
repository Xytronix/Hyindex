// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.random.Random

/**
 * OpenAI-compatible `/v1/embeddings` client.
 * Works with OpenAI, Azure OpenAI, Mistral, Mixedbread, Jina, and other
 * endpoints that expose the same OpenAI embeddings request/response shape.
 * Optional Jina fields: `task` + `late_chunking`.
 */
class OpenAICompatibleProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val model: String = "text-embedding-3-large",
    private val batchSize: Int = 128,
    private val maxChars: Int = 32000,
    private val maxRetries: Int = 3,
    private val concurrency: Int = 4,
    private val dimensions: Int? = null,
    private val taskForDocuments: String? = null,
    private val taskForQueries: String? = null,
    private val lateChunking: Boolean = false,
    private val log: LogProvider = StdoutLogProvider,
) : EmbeddingProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()
    private val normalizedBase = baseUrl.trimEnd('/')

    override val modelId: String get() = model

    override val dimension: Int
        get() = dimensions ?: KNOWN_DIMENSIONS[model] ?: 1536

    override val batchConcurrency: Int get() = concurrency

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        for (chunk in texts.chunked(batchSize)) {
            out += embedBatch(chunk.map { it.take(maxChars) }, taskForDocuments, lateChunking)
        }
        return out
    }

    override suspend fun embedQuery(query: String): FloatArray =
        embedBatch(listOf(query.take(maxChars)), taskForQueries, lateChunking = false).single()

    override suspend fun validate() {
        embedBatch(listOf("test"), taskForQueries ?: taskForDocuments, lateChunking = false)
    }

    private suspend fun embedBatch(
        texts: List<String>,
        task: String?,
        lateChunking: Boolean,
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        val requestBody = buildJsonObject {
            put("model", model)
            put("input", JsonArray(texts.map { JsonPrimitive(it) }))
            dimensions?.let { put("dimensions", it) }
            if (task.isNullOrBlank().not()) put("task", task)
            if (lateChunking) put("late_chunking", true)
        }.toString()

        var lastException: Exception? = null
        for (attempt in 0 until maxRetries) {
            try {
                val builder = HttpRequest.newBuilder()
                    .uri(URI.create("$normalizedBase/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                if (apiKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer $apiKey")
                }
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

                when (response.statusCode()) {
                    200 -> {
                        val parsed = json.parseToJsonElement(response.body()).jsonObject
                        return@withContext parsed["data"]!!.jsonArray
                            .sortedBy { it.jsonObject["index"]!!.jsonPrimitive.int }
                            .map { entry ->
                                entry.jsonObject["embedding"]!!.jsonArray
                                    .map { it.jsonPrimitive.float }
                                    .toFloatArray()
                            }
                    }
                    401, 403 -> throw EmbeddingException.InvalidApiKey()
                    429 -> {
                        val retryAfter = response.headers().firstValue("retry-after")
                            .map { it.toLongOrNull()?.times(1000) ?: 5000L }
                            .orElse(5000L)
                        val jittered = retryAfter + Random.nextLong(0, 500)
                        log.warn("Embedding provider rate limited, retrying in ${jittered}ms")
                        delay(jittered)
                        lastException = EmbeddingException.RateLimited(retryAfter)
                        continue
                    }
                    else -> throw EmbeddingException.ApiError(response.statusCode(), response.body())
                }
            } catch (e: EmbeddingException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val backoff = (1000L shl attempt) + Random.nextLong(0, 500)
                    delay(backoff)
                }
            }
        }
        throw EmbeddingException.ConnectionFailed("$normalizedBase/v1/embeddings", lastException)
    }

    companion object {
        private val KNOWN_DIMENSIONS = mapOf(
            "text-embedding-3-small" to 1536,
            "text-embedding-3-large" to 3072,
            "text-embedding-ada-002" to 1536,
            "mistral-embed" to 1024,
            "codestral-embed-2505" to 1536,
            "jina-embeddings-v3" to 1024,
            "jina-embeddings-v2-base-en" to 768,
            "jina-embeddings-v2-base-code" to 768,
            "mxbai-embed-large" to 1024,
            "mxbai-embed-large-v1" to 1024,
        )
    }
}
