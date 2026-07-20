// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
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

class VoyageReranker(
    private val apiKey: String,
    private val model: String,
    private val log: LogProvider = StdoutLogProvider,
    private val baseUrl: String = "https://api.voyageai.com",
) : Reranker {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newHttpClient()

    override fun rerank(query: String, documents: List<String>): List<Pair<Int, Double>> {
        if (apiKey.isBlank() || documents.isEmpty()) return emptyList()

        return try {
            val requestBody = buildJsonObject {
                put("query", query)
                put("documents", JsonArray(documents.map { JsonPrimitive(it) }))
                put("model", model)
            }.toString()

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/rerank"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("VoyageAI rerank failed: HTTP ${response.statusCode()}")
                return emptyList()
            }

            val parsed = json.parseToJsonElement(response.body()).jsonObject
            // Voyage returns ranked results under "data"; bifrost returns "results".
            val ranked = (parsed["data"] ?: parsed["results"])?.jsonArray ?: return emptyList()
            ranked
                .sortedByDescending { it.jsonObject["relevance_score"]!!.jsonPrimitive.double }
                .map {
                    it.jsonObject["index"]!!.jsonPrimitive.int to
                        it.jsonObject["relevance_score"]!!.jsonPrimitive.double
                }
        } catch (e: Exception) {
            log.warn("VoyageAI rerank failed: ${e.message}")
            emptyList()
        }
    }

}
