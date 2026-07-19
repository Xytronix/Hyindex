// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.embedding

import com.hyve.knowledge.core.logging.LogProvider
import com.hyve.knowledge.core.logging.StdoutLogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.random.Random

/**
 * Google Gemini native `:embedContent` / `:batchEmbedContents` client.
 * For Google's OpenAI-compatible shim, point embeddingBaseUrl at `.../v1beta/openai`
 * and use embeddingProvider=gemini (routed via OpenAICompatibleProvider).
 */
class GeminiEmbeddingProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val model: String = "text-embedding-004",
    private val batchSize: Int = 100,
    private val maxChars: Int = 32000,
    private val maxRetries: Int = 3,
    private val concurrency: Int = 4,
    private val dimensions: Int? = null,
    private val log: LogProvider = StdoutLogProvider,
) : EmbeddingProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()
    private val normalizedBase = baseUrl.trimEnd('/')
    private val modelPath = if (model.startsWith("models/")) model else "models/$model"

    override val modelId: String get() = model

    override val dimension: Int
        get() = dimensions ?: KNOWN_DIMENSIONS[model.removePrefix("models/")] ?: 768

    override val batchConcurrency: Int get() = concurrency

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        for (chunk in texts.chunked(batchSize)) {
            out += embedBatch(chunk.map { it.take(maxChars) }, taskType = "RETRIEVAL_DOCUMENT")
        }
        return out
    }

    override suspend fun embedQuery(query: String): FloatArray =
        embedBatch(listOf(query.take(maxChars)), taskType = "RETRIEVAL_QUERY").single()

    override suspend fun validate() {
        embedBatch(listOf("test"), taskType = "RETRIEVAL_QUERY")
    }

    private suspend fun embedBatch(texts: List<String>, taskType: String): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.size == 1) {
            return@withContext listOf(embedOne(texts.single(), taskType))
        }

        val requestBody = buildJsonObject {
            putJsonArray("requests") {
                texts.forEach { text ->
                    add(buildJsonObject {
                        put("model", modelPath)
                        putJsonObject("content") {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", text) })
                            }
                        }
                        put("taskType", taskType)
                        dimensions?.let { put("outputDimensionality", it) }
                    })
                }
            }
        }.toString()

        val url = "$normalizedBase/$modelPath:batchEmbedContents"
        val parsed = postJson(url, requestBody)
        val embeddings = parsed["embeddings"]?.jsonArray
            ?: throw EmbeddingException.ApiError(200, "missing embeddings in Gemini batch response")
        embeddings.map { entry ->
            entry.jsonObject["values"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
    }

    private suspend fun embedOne(text: String, taskType: String): FloatArray {
        val requestBody = buildJsonObject {
            put("model", modelPath)
            putJsonObject("content") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", text) })
                }
            }
            put("taskType", taskType)
            dimensions?.let { put("outputDimensionality", it) }
        }.toString()
        val url = "$normalizedBase/$modelPath:embedContent"
        val parsed = postJson(url, requestBody)
        val values = parsed["embedding"]?.jsonObject?.get("values")?.jsonArray
            ?: throw EmbeddingException.ApiError(200, "missing embedding.values in Gemini response")
        return values.map { it.jsonPrimitive.float }.toFloatArray()
    }

    private suspend fun postJson(url: String, requestBody: String): kotlinx.serialization.json.JsonObject {
        var lastException: Exception? = null
        for (attempt in 0 until maxRetries) {
            try {
                val builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                if (apiKey.isNotBlank()) {
                    builder.header("x-goog-api-key", apiKey)
                }
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                when (response.statusCode()) {
                    200 -> return json.parseToJsonElement(response.body()).jsonObject
                    401, 403 -> throw EmbeddingException.InvalidApiKey()
                    429 -> {
                        val retryAfter = response.headers().firstValue("retry-after")
                            .map { it.toLongOrNull()?.times(1000) ?: 5000L }
                            .orElse(5000L)
                        val jittered = retryAfter + Random.nextLong(0, 500)
                        log.warn("Gemini rate limited, retrying in ${jittered}ms")
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
        throw EmbeddingException.ConnectionFailed(url, lastException)
    }

    companion object {
        private val KNOWN_DIMENSIONS = mapOf(
            "text-embedding-004" to 768,
            "text-embedding-005" to 768,
            "gemini-embedding-001" to 3072,
            "gemini-embedding-2" to 3072,
        )
    }
}
