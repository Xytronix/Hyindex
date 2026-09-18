// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.config.RerankerProfile
import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

interface Reranker {
    fun rerank(query: String, documents: List<String>): List<Pair<Int, Double>>
}

object RerankerFactory {
    fun fromProfile(
        profile: RerankerProfile,
        log: LogProvider = StdoutLogProvider,
    ): Reranker {
        val provider = profile.provider.trim().lowercase()
        require(provider.isNotEmpty()) { "Reranker profile has no provider" }
        require(profile.model.isNotBlank()) { "Reranker profile has no model" }
        require(profile.topN > 0) { "Reranker profile topN must be positive" }

        val protocol = profile.protocol.trim().lowercase().ifEmpty {
            when (provider) {
                "voyage" -> "voyage"
                "cohere" -> "cohere"
                "jina" -> "jina"
                else -> throw IllegalArgumentException(
                    "Reranker provider '$provider' must declare protocol=voyage, cohere, or jina",
                )
            }
        }
        require(protocol in SUPPORTED_PROTOCOLS) {
            "Unsupported reranker protocol '$protocol'; expected voyage, cohere, or jina"
        }

        val baseUrl = profile.baseUrl.ifBlank {
            when (provider) {
                "voyage" -> "https://api.voyageai.com"
                "cohere" -> "https://api.cohere.com"
                "jina" -> "https://api.jina.ai"
                else -> throw IllegalArgumentException("Reranker provider '$provider' has no baseUrl")
            }
        }
        val defaultEndpoint = when (protocol) {
            "cohere" -> "/v2/rerank"
            else -> "/v1/rerank"
        }
        val endpoint = profile.endpoint.ifBlank { defaultEndpoint }
        val url = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            endpoint
        } else {
            "${baseUrl.trimEnd('/')}/${endpoint.trimStart('/')}"
        }
        return HttpReranker(
            provider = provider,
            protocol = protocol,
            url = url,
            apiKey = profile.apiKey,
            model = profile.model,
            log = log,
        )
    }

    private val SUPPORTED_PROTOCOLS = setOf("voyage", "cohere", "jina")
}

internal class HttpReranker(
    private val provider: String,
    private val protocol: String,
    private val url: String,
    private val apiKey: String,
    private val model: String,
    private val log: LogProvider = StdoutLogProvider,
) : Reranker {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()

    override fun rerank(query: String, documents: List<String>): List<Pair<Int, Double>> {
        if (documents.isEmpty()) return emptyList()

        return try {
            val requestBody = buildJsonObject {
                put("query", query)
                put("documents", JsonArray(documents.map(::JsonPrimitive)))
                put("model", model)
                when (protocol) {
                    "voyage" -> put("top_k", documents.size)
                    else -> put("top_n", documents.size)
                }
            }.toString()

            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
            if (apiKey.isNotBlank()) requestBuilder.header("Authorization", "Bearer $apiKey")
            val response = client.send(
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() !in 200..299) {
                log.warn("$provider rerank failed: HTTP ${response.statusCode()}")
                return emptyList()
            }

            val parsed = json.parseToJsonElement(response.body()).jsonObject
            val ranked = (parsed["data"] ?: parsed["results"])?.jsonArray ?: return emptyList()
            ranked.mapNotNull { item ->
                runCatching {
                    val result = item.jsonObject
                    val index = result["index"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
                    val score = (result["relevance_score"] ?: result["score"])
                        ?.jsonPrimitive
                        ?.doubleOrNull
                        ?: return@runCatching null
                    index to score
                }.getOrNull()
            }.sortedByDescending { it.second }
        } catch (e: Exception) {
            log.warn("$provider rerank failed: ${e.message}")
            emptyList()
        }
    }
}
