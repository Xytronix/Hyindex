// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.embedding

object EmbeddingCompatibility {
    const val VOYAGE_4_FAMILY = "voyage-4"
    const val GEMINI_2_FAMILY = "gemini-embedding-2"

    fun family(model: String): String {
        val id = model.trim().lowercase()
        if (id.isEmpty()) return id
        if (id == "gemini-embedding-2" || id == "gemini-embedding-2.0" || id.startsWith("gemini-embedding-2-")) {
            return GEMINI_2_FAMILY
        }
        if (id == "voyage-code-4" || id.startsWith("voyage-code-4-") ||
            id == "voyage-4" || id.startsWith("voyage-4-") ||
            id == "voyage-context-4" || id.startsWith("voyage-context-4-")) {
            return VOYAGE_4_FAMILY
        }
        return id
    }

    fun areCompatible(documentModel: String, queryModel: String): Boolean {
        val document = documentModel.trim().lowercase()
        val query = queryModel.trim().lowercase()
        if (document == query) return true
        val documentFamily = family(document)
        val queryFamily = family(query)
        if (documentFamily == GEMINI_2_FAMILY || queryFamily == GEMINI_2_FAMILY) {
            return documentFamily == GEMINI_2_FAMILY && queryFamily == GEMINI_2_FAMILY
        }
        return documentFamily == VOYAGE_4_FAMILY && queryFamily == VOYAGE_4_FAMILY
    }
}
