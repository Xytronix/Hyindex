// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GoldenQuery(
    val tool: String,
    val query: String,
    val expectedIds: List<String>,
    val patchline: String? = null,
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

    fun loadSeed(): List<GoldenQuery> {
        val stream = GoldenSet::class.java.getResourceAsStream(SEED_RESOURCE)
            ?: error("seed golden set not found on classpath at $SEED_RESOURCE")
        return parse(stream.bufferedReader().use { it.readText() })
    }
}
