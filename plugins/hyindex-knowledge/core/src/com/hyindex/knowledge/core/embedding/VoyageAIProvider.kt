// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.random.Random
import java.util.Base64

class VoyageAIProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.voyageai.com",
    private val model: String = "voyage-code-4",
    private val batchSize: Int = 128,
    private val maxChars: Int = 32000,
    private val maxRetries: Int = 3,
    private val concurrency: Int = 4,
    private val dimensions: Int? = null,
    private val log: LogProvider = StdoutLogProvider,
) : EmbeddingProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()
    private val normalizedBase = baseUrl.trimEnd('/')
    private val contextualized = model.startsWith("voyage-context")
    private val multimodal = model.startsWith("voyage-multimodal")

    override val modelId: String get() = model

    override val dimension: Int
        get() = dimensions ?: KNOWN_DIMENSIONS[model] ?: 1024

    override val batchConcurrency: Int get() = concurrency
    override val supportsContextualDocuments: Boolean get() = contextualized
    override val supportsMultimodalDocuments: Boolean get() = multimodal

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (apiKey.isBlank()) throw EmbeddingException.InvalidApiKey()
        if (multimodal) {
            return embedMultimodal(texts.map { MultimodalPart(it.take(maxChars)) }, "document")
        }
        if (contextualized) {
            return embedGrouped(texts.map { listOf(it) })
        }

        val results = mutableListOf<FloatArray>()
        val batches = texts.chunked(batchSize)

        for ((batchIdx, batch) in batches.withIndex()) {
            if (batchIdx > 0) delay(100)
            val truncated = batch.map { it.take(maxChars) }
            val embeddings = embedBatch(truncated.map { listOf(it) }, "document")
            results.addAll(embeddings)
        }

        return results
    }

    override suspend fun embedGrouped(documents: List<List<String>>): List<FloatArray> {
        if (apiKey.isBlank()) throw EmbeddingException.InvalidApiKey()
        if (documents.isEmpty()) return emptyList()
        if (multimodal || !contextualized) return embed(documents.flatten())

        val results = mutableListOf<FloatArray>()
        val batches = contextualBatches(documents)
        for ((batchIndex, batch) in batches.withIndex()) {
            if (batchIndex > 0) delay(100)
            results += embedBatch(batch, "document")
        }
        return results
    }

    private fun contextualBatches(documents: List<List<String>>): List<List<List<String>>> {
        val windows = documents.flatMap { document ->
            val out = mutableListOf<List<String>>()
            var current = mutableListOf<String>()
            var currentChars = 0
            for (rawChunk in document) {
                val chunk = rawChunk.take(maxChars)
                if (current.isNotEmpty() && currentChars + chunk.length > maxChars) {
                    out += current
                    current = mutableListOf()
                    currentChars = 0
                }
                current += chunk
                currentChars += chunk.length
            }
            if (current.isNotEmpty()) out += current
            out
        }
        val batches = mutableListOf<List<List<String>>>()
        var current = mutableListOf<List<String>>()
        var currentChars = 0
        for (window in windows) {
            val windowChars = window.sumOf(String::length)
            if (current.isNotEmpty() && (current.size >= batchSize || currentChars + windowChars > maxChars)) {
                batches += current
                current = mutableListOf()
                currentChars = 0
            }
            current += window
            currentChars += windowChars
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    override suspend fun embedQuery(query: String): FloatArray {
        if (apiKey.isBlank()) throw EmbeddingException.InvalidApiKey()
        if (multimodal) {
            return embedMultimodal(listOf(MultimodalPart(query.take(maxChars))), "query").first()
        }
        return embedBatch(listOf(listOf(query.take(maxChars))), "query").first()
    }

    override suspend fun embedDocuments(docs: List<MultimodalDocument>): List<FloatArray> {
        if (apiKey.isBlank()) throw EmbeddingException.InvalidApiKey()
        if (docs.isEmpty()) return emptyList()
        if (!multimodal) return embed(docs.map { it.text })
        val results = mutableListOf<FloatArray>()
        for ((batchIndex, batch) in docs.chunked(batchSize).withIndex()) {
            if (batchIndex > 0) delay(100)
            val parts = batch.map { doc ->
                MultimodalPart(
                    text = doc.text.take(maxChars),
                    imageBase64 = Base64.getEncoder().encodeToString(doc.imageBytes),
                    mediaType = doc.mediaType,
                )
            }
            results += embedMultimodal(parts, "document")
        }
        return results
    }

    override suspend fun validate() {
        if (apiKey.isBlank()) throw EmbeddingException.InvalidApiKey()
        if (multimodal) {
            embedMultimodal(listOf(MultimodalPart("test")), "query")
        } else {
            embedBatch(listOf(listOf("test")), "query")
        }
    }

    private suspend fun embedBatch(
        documents: List<List<String>>,
        inputType: String,
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        val endpoint = if (contextualized) {
            "$normalizedBase/v1/contextualizedembeddings"
        } else {
            "$normalizedBase/v1/embeddings"
        }
        val requestBody = buildJsonObject {
            put("model", model)
            put("input_type", inputType)
            dimensions?.let { put("output_dimension", it) }
            if (contextualized) {
                put(
                    "inputs",
                    JsonArray(
                        documents.map { doc ->
                            JsonArray(doc.map { JsonPrimitive(it) })
                        },
                    ),
                )
            } else {
                put("input", JsonArray(documents.flatten().map { JsonPrimitive(it) }))
            }
        }.toString()

        var lastException: Exception? = null
        for (attempt in 0 until maxRetries) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

                when (response.statusCode()) {
                    200 -> {
                        val parsed = json.parseToJsonElement(response.body()).jsonObject
                        return@withContext if (contextualized) {
                            parseContextualized(parsed)
                        } else {
                            parsed["data"]!!.jsonArray
                                .sortedBy { it.jsonObject["index"]!!.jsonPrimitive.int }
                                .map { entry ->
                                    entry.jsonObject["embedding"]!!.jsonArray
                                        .map { it.jsonPrimitive.float }
                                        .toFloatArray()
                                }
                        }
                    }
                    401 -> throw EmbeddingException.InvalidApiKey()
                    429 -> {
                        val retryAfter = response.headers().firstValue("retry-after")
                            .map { it.toLongOrNull()?.times(1000) ?: 5000L }
                            .orElse(5000L)
                        val jittered = retryAfter + Random.nextLong(0, 500)
                        log.warn("VoyageAI rate limited, retrying in ${jittered}ms")
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
        throw EmbeddingException.ConnectionFailed(endpoint, lastException)
    }

    private fun parseContextualized(parsed: kotlinx.serialization.json.JsonObject): List<FloatArray> {
        val outer = parsed["data"]!!.jsonArray
        return outer.sortedBy { it.jsonObject["index"]?.jsonPrimitive?.int ?: 0 }.flatMap { document ->
            val entries = document.jsonObject["data"]?.jsonArray
                ?.sortedBy { it.jsonObject["index"]?.jsonPrimitive?.int ?: 0 }
                ?: listOf(document)
            entries.map { entry ->
                entry.jsonObject["embedding"]!!.jsonArray
                    .map { it.jsonPrimitive.float }
                    .toFloatArray()
            }
        }
    }

    private data class MultimodalPart(
        val text: String,
        val imageBase64: String? = null,
        val mediaType: String = "image/png",
    )

    private suspend fun embedMultimodal(
        parts: List<MultimodalPart>,
        inputType: String,
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        val endpoint = "$normalizedBase/v1/multimodalembeddings"
        val requestBody = buildJsonObject {
            put("model", model)
            put("input_type", inputType)
            put(
                "inputs",
                JsonArray(
                    parts.map { part ->
                        val content = mutableListOf(
                            buildJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            },
                        )
                        if (part.imageBase64 != null) {
                            content += buildJsonObject {
                                put("type", "image_base64")
                                put("image_base64", "data:${part.mediaType};base64,${part.imageBase64}")
                            }
                        }
                        buildJsonObject {
                            put("content", JsonArray(content))
                        }
                    },
                ),
            )
        }.toString()

        var lastException: Exception? = null
        for (attempt in 0 until maxRetries) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

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
                    401 -> throw EmbeddingException.InvalidApiKey()
                    429 -> {
                        val retryAfter = response.headers().firstValue("retry-after")
                            .map { it.toLongOrNull()?.times(1000) ?: 5000L }
                            .orElse(5000L)
                        val jittered = retryAfter + Random.nextLong(0, 500)
                        log.warn("VoyageAI rate limited, retrying in ${jittered}ms")
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
        throw EmbeddingException.ConnectionFailed(endpoint, lastException)
    }

    companion object {
        private val KNOWN_DIMENSIONS = mapOf(
            "voyage-code-4" to 1024,
            "voyage-code-3" to 1024,
            "voyage-3" to 1024,
            "voyage-3-large" to 1024,
            "voyage-3.5" to 1024,
            "voyage-3.5-lite" to 1024,
            "voyage-4" to 1024,
            "voyage-4-large" to 1024,
            "voyage-4-lite" to 1024,
            "voyage-4-nano" to 1024,
            "voyage-context-3" to 1024,
            "voyage-context-4" to 1024,
            "voyage-finance-2" to 1024,
            "voyage-law-2" to 1024,
            "voyage-multimodal-3" to 1024,
            "voyage-multimodal-3.5" to 1024,
        )
    }
}
