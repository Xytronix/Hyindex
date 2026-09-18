// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GradedJudgment(
    val id: String,
    val grade: Int,
)

@Serializable
data class GoldenQuery(
    val tool: String,
    val query: String,
    val expectedIds: List<String>,
    val patchline: String? = null,
    val intent: String? = null,
    val expectedCorpora: List<String>? = null,
    val judgments: List<GradedJudgment>? = null,
)

object GoldenSet {
    const val SEED_RESOURCE = "/golden/seed.jsonl"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<GoldenQuery> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { json.decodeFromString(GoldenQuery.serializer(), it) }
            .toList()

    fun load(path: String): List<GoldenQuery> = parse(File(path).readText())

    fun loadSeed(): List<GoldenQuery> = loadResource(SEED_RESOURCE)


    private fun loadResource(resource: String): List<GoldenQuery> {
        val stream = GoldenSet::class.java.getResourceAsStream(resource)
            ?: error("golden set not found on classpath at $resource")
        return parse(stream.bufferedReader().use { it.readText() })
    }
}
