// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.index

import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.extraction.ClientUIChunk
import com.hyve.knowledge.extraction.ClientUIType


class UIBindingExtractor(private val db: KnowledgeDatabase) {

    private val analyzer = UIContentAnalyzer()

    data class EdgeRow(
        val sourceId: String,
        val targetId: String,
        val edgeType: String,
        val metadata: String? = null,
        val targetResolved: Boolean = true,
    )


    fun extractEdges(chunks: List<ClientUIChunk>): List<EdgeRow> {
        val uiChunks = chunks.filter { it.type == ClientUIType.UI }
        if (uiChunks.isEmpty()) return emptyList()


        val gamedataLookup = db.query(
            "SELECT id, display_name FROM nodes WHERE corpus = 'gamedata' AND display_name IS NOT NULL",
        ) { rs -> rs.getString("display_name") to rs.getString("id") }
            .groupBy({ it.first.lowercase() }, { it.second })


        val edgeMap = mutableMapOf<Pair<String, String>, EdgeRow>()

        for (chunk in uiChunks) {
            if (chunk.content.isBlank()) continue

            val candidates = analyzer.analyze(chunk.content, chunk.id)
            for (candidate in candidates) {
                val targetIds = gamedataLookup[candidate.candidateText.lowercase()] ?: continue
                for (targetId in targetIds) {
                    val key = candidate.clientNodeId to targetId
                    val existing = edgeMap[key]

                    if (existing == null || candidate.confidence > parseConfidence(existing.metadata)) {
                        edgeMap[key] = EdgeRow(
                            sourceId = candidate.clientNodeId,
                            targetId = targetId,
                            edgeType = "UI_BINDS_TO",
                            metadata = """{"strategy":"${candidate.strategy}","confidence":${candidate.confidence}}""",
                            targetResolved = true,
                        )
                    }
                }
            }
        }

        return edgeMap.values.toList()
    }

    private fun parseConfidence(metadata: String?): Float {
        if (metadata == null) return 0f
        val match = Regex(""""confidence":\s*([0-9.]+)""").find(metadata)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }
}
