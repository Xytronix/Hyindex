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
 * Cohere `/v1/embed` client with asymmetric `input_type` support.
 */
class CohereEmbeddingProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.cohere.com",
    private val model: String = "embed-v4.0",
    private val batchSize: Int = 96,
    private val maxChars: Int = 32000,
    private val maxRetries: Int = 3,
    private val concurrency: Int = 4,
    private val log: LogProvider = StdoutLogProvider,
) : EmbeddingProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()
    private val normalizedBase = baseUrl.trimEnd('/')

    override val modelId: String get() = model

    override val dimension: Int
        get() = KNOWN_DIMENSIONS[model] ?: 1024

    override val batchConcurrency: Int get() = concurrency

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        for (chunk in texts.chunked(batchSize)) {
            out += embedBatch(chunk.map { it.take(maxChars) }, inputType = "search_document")
        }
        return out
    }

    override suspend fun embedQuery(query: String): FloatArray =
        embedBatch(listOf(query.take(maxChars)), inputType = "search_query").single()

    override suspend fun validate() {
        embedBatch(listOf("test"), inputType = "search_query")
    }

    private suspend fun embedBatch(texts: List<String>, inputType: String): List<FloatArray> = withContext(Dispatchers.IO) {
        val requestBody = buildJsonObject {
            put("model", model)
            put("texts", JsonArray(texts.map { JsonPrimitive(it) }))
            put("input_type", inputType)
            put("embedding_types", JsonArray(listOf(JsonPrimitive("float"))))
        }.toString()

        var lastException: Exception? = null
        for (attempt in 0 until maxRetries) {
            try {
                val builder = HttpRequest.newBuilder()
                    .uri(URI.create("$normalizedBase/v1/embed"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                if (apiKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer $apiKey")
                }
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

                when (response.statusCode()) {
                    200 -> {
                        val parsed = json.parseToJsonElement(response.body()).jsonObject
                        val embeddingsObj = parsed["embeddings"]?.jsonObject
                        val floatArrays = embeddingsObj?.get("float")?.jsonArray
                        if (floatArrays != null) {
                            return@withContext floatArrays.map { row ->
                                row.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                            }
                        }
                        // Legacy flat embeddings array
                        val legacy = parsed["embeddings"]?.jsonArray
                            ?: throw EmbeddingException.ApiError(200, "missing embeddings in response")
                        return@withContext legacy.map { row ->
                            row.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                        }
                    }
                    401, 403 -> throw EmbeddingException.InvalidApiKey()
                    429 -> {
                        val retryAfter = response.headers().firstValue("retry-after")
                            .map { it.toLongOrNull()?.times(1000) ?: 5000L }
                            .orElse(5000L)
                        val jittered = retryAfter + Random.nextLong(0, 500)
                        log.warn("Cohere rate limited, retrying in ${jittered}ms")
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
        throw EmbeddingException.ConnectionFailed("$normalizedBase/v1/embed", lastException)
    }

    companion object {
        private val KNOWN_DIMENSIONS = mapOf(
            "embed-v4.0" to 1024,
            "embed-english-v3.0" to 1024,
            "embed-multilingual-v3.0" to 1024,
            "embed-english-light-v3.0" to 384,
            "embed-multilingual-light-v3.0" to 384,
        )
    }
}
