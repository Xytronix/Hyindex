// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.index

import com.hyve.knowledge.core.db.KnowledgeDatabase

object FtsTokenizer {

    data class FtsRow(val nodeId: String, val name: String, val body: String)

    fun populate(db: KnowledgeDatabase, corpus: String, rows: List<FtsRow>, splitBody: Boolean) {
        if (rows.isEmpty()) return
        val deduped = rows.distinctBy { it.nodeId }
        db.inTransaction { conn ->
            conn.prepareStatement("DELETE FROM nodes_fts WHERE corpus = ?").use { ps ->
                ps.setString(1, corpus)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO nodes_fts(node_id, corpus, name, body) VALUES(?, ?, ?, ?)"
            ).use { ps ->
                for ((idx, row) in deduped.withIndex()) {
                    ps.setString(1, row.nodeId)
                    ps.setString(2, corpus)
                    ps.setString(3, if (splitBody) splitIdentifiers(row.name) else row.name)
                    ps.setString(4, if (splitBody) splitIdentifiers(row.body) else row.body)
                    ps.addBatch()
                    if ((idx + 1) % 2000 == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
        }
    }

    private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
    private val ACRONYM_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")
    private val NON_ALNUM = Regex("[^A-Za-z0-9]+")

    fun splitIdentifiers(text: String): String {
        if (text.isBlank()) return text
        val extra = StringBuilder()
        val tokens = text.split(Regex("\\s+"))
        for (token in tokens) {
            val subWords = subWordsOf(token)
            if (subWords.size > 1) {
                for (word in subWords) {
                    extra.append(' ')
                    extra.append(word)
                }
            }
        }
        return if (extra.isEmpty()) text else text + extra.toString()
    }

    fun subWordsOf(token: String): List<String> {
        val spaced = ACRONYM_BOUNDARY.replace(CAMEL_BOUNDARY.replace(token, "$1 $2"), "$1 $2")
        return NON_ALNUM.replace(spaced, " ").trim().split(' ').filter { it.isNotBlank() }.map { it.lowercase() }
    }
}
