// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class CorpusRoute(
    val corpora: List<Corpus>,
    val probabilities: Map<Corpus, Double>,
    val intent: String,
    val graphExpansionProbability: Double,
)

fun interface CorpusRouter {
    fun route(query: String, availableCorpora: List<Corpus>): CorpusRoute?
}

class JevCorpusRouter(
    private val apiKey: String,
    private val model: String = "jev-latest",
    baseUrl: String = "https://api.typesafe.ai",
    private val threshold: Double = 0.20,
    private val log: LogProvider = StdoutLogProvider,
) : CorpusRouter {
    private val endpoint = "${baseUrl.trimEnd('/')}/v1/systemone"
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    override fun route(query: String, availableCorpora: List<Corpus>): CorpusRoute? {
        if (apiKey.isBlank() || query.isBlank() || availableCorpora.isEmpty()) return null
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(query)))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("Jev corpus routing failed: HTTP ${response.statusCode()}")
                null
            } else {
                parseRoute(response.body(), availableCorpora, threshold)
            }
        } catch (e: Exception) {
            log.warn("Jev corpus routing failed: ${e.message}")
            null
        }
    }

    private fun requestBody(query: String): String = buildJsonObject {
        put("state", buildJsonObject { put("query", query) })
        put("model", model)
        put("questions", buildJsonObject {
            put("corpus_code", corpusQuestion(
                "Should the decompiled Hytale server Java code corpus be searched to answer this mod-development query?",
                "Server APIs or implementation details are likely useful.",
                "Server source is unlikely to help.",
            ))
            put("corpus_docs", corpusQuestion(
                "Should Hytale modding guides and technical documentation be searched to answer this query?",
                "Guides or documentation are likely useful.",
                "Documentation is unlikely to help.",
            ))
            put("corpus_gamedata", corpusQuestion(
                "Should Hytale game-data definitions be searched to answer this query?",
                "Assets, JSON, recipes, items, NPCs, blocks, drops, or world data are relevant.",
                "Game data is unlikely to help.",
            ))
            put("corpus_client", corpusQuestion(
                "Should Hytale client UI source and layout files be searched to answer this query?",
                "Client UI implementation is relevant.",
                "Client UI is unlikely to help.",
            ))
            put("intent", buildJsonObject {
                put("type", "choice")
                put("instructions", "Classify the primary information need.")
                put("criteria", buildJsonObject {
                    put("api_how_to", "How to implement a mod feature with an API.")
                    put("exact_symbol", "Locate a named class, method, field, or file.")
                    put("implementation", "Find where behavior is implemented or validated internally.")
                    put("game_data", "Find or define assets, recipes, NPCs, drops, items, blocks, or other game data.")
                    put("client_ui", "Find or modify client UI behavior or layouts.")
                })
            })
            put("graph_expansion", corpusQuestion(
                "Would following relationships from direct search hits likely be necessary to answer this query?",
                "Related symbols or cross-corpus graph edges are likely needed.",
                "Direct search results should be sufficient.",
            ))
        })
    }.toString()

    private fun corpusQuestion(instructions: String, yes: String, no: String): JsonObject = buildJsonObject {
        put("type", "noul")
        put("instructions", instructions)
        put("criteria", buildJsonObject {
            put("true", yes)
            put("false", no)
        })
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val answerKeys = mapOf(
            Corpus.CODE to "corpus_code",
            Corpus.DOCS to "corpus_docs",
            Corpus.GAMEDATA to "corpus_gamedata",
            Corpus.CLIENT to "corpus_client",
        )

        internal fun parseRoute(response: String, availableCorpora: List<Corpus>, threshold: Double): CorpusRoute? {
            val answers = runCatching {
                json.parseToJsonElement(response).jsonObject["answers"]?.jsonObject
            }.getOrNull() ?: return null
            val probabilities = availableCorpora.mapNotNull { corpus ->
                val key = answerKeys[corpus] ?: return@mapNotNull null
                val probability = answers[key]?.jsonObject?.get("noul")?.jsonPrimitive?.doubleOrNull
                    ?: return@mapNotNull null
                corpus to probability
            }.toMap()
            if (probabilities.isEmpty()) return null

            val intent = answers["intent"]?.jsonObject?.get("choice")?.jsonPrimitive?.content ?: "unknown"
            val requiredByIntent = when (intent) {
                "api_how_to" -> listOf(Corpus.DOCS, Corpus.CODE)
                "exact_symbol", "implementation" -> listOf(Corpus.CODE)
                "game_data" -> listOf(Corpus.GAMEDATA)
                "client_ui" -> listOf(Corpus.CLIENT)
                else -> emptyList()
            }
            val selected = (probabilities.filterValues { it >= threshold }.keys + requiredByIntent)
                .filter { it in availableCorpora }
                .distinct()
                .ifEmpty { listOf(probabilities.maxBy { it.value }.key) }
                .sortedWith(
                    compareByDescending<Corpus> { probabilities[it] ?: 0.0 }
                        .thenBy { availableCorpora.indexOf(it) },
                )
            val graphExpansionProbability = answers["graph_expansion"]?.jsonObject
                ?.get("noul")?.jsonPrimitive?.doubleOrNull ?: 0.0
            return CorpusRoute(selected, probabilities, intent, graphExpansionProbability)
        }
    }
}
