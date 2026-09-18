// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.db.EmbeddingPurpose
import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.Base64
import kotlin.random.Random

/**
 * Google Gemini native `:embedContent` / `:batchEmbedContents` client.
 * For Google's OpenAI-compatible shim, set a Gemini profile baseUrl to
 * `.../v1beta/openai`; the factory routes that profile through OpenAICompatibleProvider.
 */
class GeminiEmbeddingProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val model: String = "text-embedding-004",
    private val batchSize: Int = 100,
    private val maxChars: Int = 32000,
    private val maxRetries: Int = 8,
    private val concurrency: Int = 4,
    private val dimensions: Int? = null,
    private val purpose: EmbeddingPurpose = EmbeddingPurpose.CODE,
    private val log: LogProvider = StdoutLogProvider,
) : EmbeddingProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()
    private val normalizedBase = baseUrl.trimEnd('/')
    private val modelPath = if (model.startsWith("models/")) model else "models/$model"
    private val isEmbedding2 = model.removePrefix("models/").startsWith("gemini-embedding-2")

    override val modelId: String get() = model

    override val dimension: Int
        get() = dimensions ?: if (isEmbedding2) 1024 else (KNOWN_DIMENSIONS[model.removePrefix("models/")] ?: 768)

    override val batchConcurrency: Int get() = concurrency
    override val supportsMultimodalDocuments: Boolean get() = isEmbedding2

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        for (chunk in texts.chunked(batchSize)) {
            out += embedBatch(chunk.map { formatDocument(it.take(maxChars)) }, taskType = "RETRIEVAL_DOCUMENT")
        }
        return out
    }

    override suspend fun embedQuery(query: String): FloatArray =
        embedBatch(listOf(formatQuery(query.take(maxChars))), taskType = "RETRIEVAL_QUERY").single()

    override suspend fun validate() {
        embedBatch(listOf(formatQuery("test")), taskType = "RETRIEVAL_QUERY")
    }

    override suspend fun embedDocuments(docs: List<MultimodalDocument>): List<FloatArray> {
        if (docs.isEmpty()) return emptyList()
        if (!isEmbedding2) return embed(docs.map { it.text })
        val out = ArrayList<FloatArray>(docs.size)
        for (chunk in docs.chunked(batchSize)) {
            out += embedMultimodalBatch(chunk)
        }
        return out
    }

    private fun formatQuery(query: String): String {
        if (!isEmbedding2) return query
        return when (purpose) {
            EmbeddingPurpose.CODE -> "task: code retrieval | query: $query"
            EmbeddingPurpose.TEXT, EmbeddingPurpose.IMAGE -> "task: search result | query: $query"
        }
    }

    private fun formatDocument(text: String): String {
        if (!isEmbedding2) return text
        return "title: none | text: $text"
    }

    private suspend fun embedBatch(texts: List<String>, taskType: String): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.size == 1) {
            return@withContext listOf(embedOneText(texts.single(), taskType))
        }

        val requestBody = buildJsonObject {
            putJsonArray("requests") {
                texts.forEach { text ->
                    add(requestObject(textContent(text), taskType))
                }
            }
        }.toString()

        val url = "$normalizedBase/$modelPath:batchEmbedContents"
        val parsed = postJson(url, requestBody)
        parseBatchEmbeddings(parsed)
    }

    private suspend fun embedMultimodalBatch(docs: List<MultimodalDocument>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (docs.size == 1) {
            return@withContext listOf(embedOneParts(multimodalParts(docs.single()), "RETRIEVAL_DOCUMENT"))
        }
        val requestBody = buildJsonObject {
            putJsonArray("requests") {
                docs.forEach { doc ->
                    add(requestObject(multimodalParts(doc), "RETRIEVAL_DOCUMENT"))
                }
            }
        }.toString()
        val parsed = postJson("$normalizedBase/$modelPath:batchEmbedContents", requestBody)
        parseBatchEmbeddings(parsed)
    }

    private suspend fun embedOneText(text: String, taskType: String): FloatArray =
        embedOneParts(textContent(text), taskType)

    private suspend fun embedOneParts(content: JsonObject, taskType: String): FloatArray {
        val requestBody = requestObject(content, taskType).toString()
        val url = "$normalizedBase/$modelPath:embedContent"
        val parsed = postJson(url, requestBody)
        val values = parsed["embedding"]?.jsonObject?.get("values")?.jsonArray
            ?: throw EmbeddingException.ApiError(200, "missing embedding.values in Gemini response")
        return values.map { it.jsonPrimitive.float }.toFloatArray()
    }

    private fun requestObject(content: JsonObject, taskType: String): JsonObject = buildJsonObject {
        put("model", modelPath)
        put("content", content)
        applyTaskAndDimensions(taskType)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.applyTaskAndDimensions(taskType: String) {
        if (isEmbedding2) {
            val dim = dimensions ?: 1024
            putJsonObject("embedContentConfig") {
                put("outputDimensionality", dim)
            }
        } else {
            put("taskType", taskType)
            dimensions?.let { put("outputDimensionality", it) }
        }
    }

    private fun textContent(text: String): JsonObject = buildJsonObject {
        putJsonArray("parts") {
            add(buildJsonObject { put("text", text) })
        }
    }

    private fun multimodalParts(doc: MultimodalDocument): JsonObject = buildJsonObject {
        putJsonArray("parts") {
            add(buildJsonObject { put("text", formatDocument(doc.text.take(maxChars))) })
            add(buildJsonObject {
                putJsonObject("inlineData") {
                    put("mimeType", doc.mediaType)
                    put("data", Base64.getEncoder().encodeToString(doc.imageBytes))
                }
            })
        }
    }

    private fun parseBatchEmbeddings(parsed: JsonObject): List<FloatArray> {
        val embeddings = parsed["embeddings"]?.jsonArray
            ?: throw EmbeddingException.ApiError(200, "missing embeddings in Gemini batch response")
        return embeddings.map { entry ->
            entry.jsonObject["values"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
    }

    private suspend fun postJson(url: String, requestBody: String): JsonObject {
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
                        val body = response.body()
                        if (isPermanentBilling429(body)) {
                            throw EmbeddingException.ApiError(429, body)
                        }
                        val headerDelay = response.headers().firstValue("retry-after")
                            .map { it.toDoubleOrNull()?.times(1000)?.toLong() }
                            .orElse(null)
                        val retryAfter = headerDelay
                            ?: retryDelayMillis(body)
                            ?: minOf(60_000L, 5_000L shl attempt.coerceAtMost(4))
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

    private fun retryDelayMillis(body: String): Long? {
        val match = RETRY_DELAY.find(body) ?: RETRY_IN.find(body) ?: return null
        return (match.groupValues[1].toDoubleOrNull()?.times(1000)?.toLong())
            ?.coerceIn(1_000L, 120_000L)
    }

    private fun isPermanentBilling429(body: String): Boolean =
        PERMANENT_BILLING_PHRASES.any { body.contains(it, ignoreCase = true) }

    companion object {
        private val KNOWN_DIMENSIONS = mapOf(
            "text-embedding-004" to 768,
            "text-embedding-005" to 768,
            "gemini-embedding-001" to 3072,
        )
        private val RETRY_DELAY = Regex("""\"retryDelay\"\s*:\s*\"([0-9.]+)s\"""")
        private val RETRY_IN = Regex("""retry in ([0-9.]+)s""", RegexOption.IGNORE_CASE)
        private val PERMANENT_BILLING_PHRASES = listOf(
            "Your prepayment credits are depleted",
            "Your project has exceeded its monthly spending cap",
        )
    }
}
