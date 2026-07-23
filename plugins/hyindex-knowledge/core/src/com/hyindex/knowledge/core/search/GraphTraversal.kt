// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.db.KnowledgeDatabase

class GraphTraversal(private val db: KnowledgeDatabase) {

    fun findByRelation(entityName: String, relation: String, limit: Int = 10): List<SearchResult> {
        val targetResults = db.query(
            """SELECT n.id, n.display_name, n.content, n.file_path, n.line_start, n.corpus, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.source_id
               WHERE e.edge_type = ?
               AND (e.target_id LIKE ? OR e.target_id LIKE ?)
               LIMIT ?""",
            relation,
            "class:$entityName",
            "class:%.$entityName",
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = rs.getString("corpus") ?: "code",
            )
        }

        if (targetResults.isNotEmpty()) return targetResults

        return db.query(
            """SELECT n.id, n.display_name, n.content, n.file_path, n.line_start, n.corpus, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.target_id
               WHERE e.edge_type = ?
               AND (e.source_id LIKE ? OR e.source_id LIKE ?)
               LIMIT ?""",
            relation,
            "class:$entityName",
            "class:%.$entityName",
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 0.9,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = rs.getString("corpus") ?: "code",
            )
        }
    }

    fun findCallers(id: String, limit: Int = 10): List<SearchResult> {
        val classMembersLike = if (id.startsWith("class:")) id.substring("class:".length) + "#%" else "NO-MATCH-SENTINEL"
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.file_path, n.line_start
               FROM edges e
               JOIN nodes n ON n.id = e.source_id
               WHERE e.edge_type = 'CALLS'
                 AND (e.target_id = ? OR e.target_id = ? OR e.target_id LIKE ?)
               GROUP BY n.id
               LIMIT ?""",
            id,
            classForm(id),
            classMembersLike,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                corpus = "code",
            )
        }
    }

    fun findCallees(id: String, limit: Int = 10): List<SearchResult> {
        return db.query(
            """SELECT COALESCE(nm.id, nc.id) AS rid,
                      COALESCE(nm.display_name, nc.display_name) AS rname,
                      COALESCE(nm.content, nc.content) AS rcontent,
                      COALESCE(nm.file_path, nc.file_path) AS rfile,
                      COALESCE(nm.line_start, nc.line_start) AS rline
               FROM edges e
               LEFT JOIN nodes nm ON nm.id = e.target_id
               LEFT JOIN nodes nc ON nc.id = 'class:' || substr(e.target_id, 1, instr(e.target_id, '#') - 1)
               WHERE e.edge_type = 'CALLS'
                 AND e.source_id = ?
                 AND COALESCE(nm.id, nc.id) IS NOT NULL
               GROUP BY rid
               LIMIT ?""",
            id,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("rid"),
                displayName = rs.getString("rname"),
                snippet = rs.getString("rcontent") ?: "",
                filePath = rs.getString("rfile") ?: "",
                lineStart = rs.getInt("rline"),
                score = 1.0,
                source = ResultSource.GRAPH,
                corpus = "code",
            )
        }
    }

    private fun classForm(id: String): String =
        if (id.startsWith("class:")) id else "class:${id.substringBefore('#')}"

    fun findImplementingCode(gamedataNodeId: String, limit: Int = 10): List<SearchResult> {
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.file_path, n.line_start
               FROM edges e
               JOIN nodes n ON n.id = e.target_id
               WHERE e.source_id = ?
                 AND e.edge_type = 'IMPLEMENTED_BY'
                 AND n.node_type = 'JavaClass'
               LIMIT ?""",
            gamedataNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                corpus = "code",
            )
        }
    }

    fun findGamedataForCode(codeNodeId: String, limit: Int = 5): List<SearchResult> {
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text,
                      n.file_path, n.line_start, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.source_id
               WHERE e.target_id = ?
                 AND e.edge_type = 'IMPLEMENTED_BY'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            codeNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text")
                    ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
    }

    fun findByName(entityName: String, limit: Int = 10): List<SearchResult> {
        return db.query(
            """SELECT id, display_name, content, file_path, line_start, corpus, data_type
               FROM nodes
               WHERE display_name = ? OR display_name LIKE ? OR display_name LIKE ?
               ORDER BY
                 CASE WHEN display_name = ? THEN 0
                      WHEN display_name LIKE ? THEN 1
                      ELSE 2
                 END
               LIMIT ?""",
            entityName,
            "$entityName#%",
            "%.$entityName",
            entityName,
            "$entityName#%",
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = rs.getString("corpus") ?: "code",
            )
        }
    }

    fun findRecipeInputs(itemNodeId: String, limit: Int = 10): List<SearchResult> {
        val forward = db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text, n.file_path, n.line_start, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.target_id
               WHERE e.source_id = ?
                 AND e.edge_type = 'REQUIRES_ITEM'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            itemNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text") ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
        val reverse = db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text, n.file_path, n.line_start, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.source_id
               WHERE e.target_id = ?
                 AND e.edge_type = 'REQUIRES_ITEM'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            itemNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text") ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 0.9,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
        return (forward + reverse).distinctBy { it.nodeId }
    }

    fun findRecipeOutputs(itemNodeId: String, limit: Int = 10): List<SearchResult> {
        val forward = db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text, n.file_path, n.line_start, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.target_id
               WHERE e.source_id = ?
                 AND e.edge_type = 'PRODUCES_ITEM'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            itemNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text") ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
        val reverse = db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text, n.file_path, n.line_start, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.source_id
               WHERE e.target_id = ?
                 AND e.edge_type = 'PRODUCES_ITEM'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            itemNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text") ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 0.9,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
        return (forward + reverse).distinctBy { it.nodeId }
    }

    fun findDropsFrom(entityNodeId: String, limit: Int = 10): List<SearchResult> {
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text, n.file_path, n.line_start, n.data_type
               FROM edges e1
               JOIN edges e2 ON e2.source_id = e1.target_id AND e2.edge_type = 'DROPS_ITEM'
               JOIN nodes n ON n.id = e2.target_id
               WHERE e1.source_id = ?
                 AND e1.edge_type = 'DROPS_ON_DEATH'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            entityNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text") ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
    }

    fun findShopsSellingItem(itemNodeId: String, limit: Int = 10): List<SearchResult> {
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text, n.file_path, n.line_start, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.source_id
               WHERE e.target_id = ?
                 AND e.edge_type = 'OFFERED_IN_SHOP'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            itemNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text") ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
    }

    fun findDocsReferences(docsNodeId: String, limit: Int = 10): List<SearchResult> {
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text, n.file_path, n.line_start, n.data_type, n.corpus
               FROM edges e
               JOIN nodes n ON n.id = e.target_id
               WHERE e.source_id = ?
                 AND e.edge_type = 'DOCS_REFERENCES'
               LIMIT ?""",
            docsNodeId,
            limit,
        ) { rs ->
            val corpus = rs.getString("corpus") ?: "code"
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text")
                    ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = corpus,
            )
        }
    }

    fun findUIBindings(clientNodeId: String, limit: Int = 10): List<SearchResult> {
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text,
                      n.file_path, n.line_start, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.target_id
               WHERE e.source_id = ?
                 AND e.edge_type = 'UI_BINDS_TO'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            clientNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text")
                    ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
    }

    fun findUIForGamedata(gamedataNodeId: String, limit: Int = 5): List<SearchResult> {
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text,
                      n.file_path, n.line_start
               FROM edges e
               JOIN nodes n ON n.id = e.source_id
               WHERE e.target_id = ?
                 AND e.edge_type = 'UI_BINDS_TO'
                 AND n.corpus = 'client'
               LIMIT ?""",
            gamedataNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text")
                    ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                corpus = "client",
            )
        }
    }

    fun findGroupMembers(groupNodeId: String, limit: Int = 10): List<SearchResult> {
        return db.query(
            """SELECT n.id, n.display_name, n.content, n.embedding_text, n.file_path, n.line_start, n.data_type
               FROM edges e
               JOIN nodes n ON n.id = e.target_id
               WHERE e.source_id = ?
                 AND e.edge_type = 'HAS_MEMBER'
                 AND n.corpus = 'gamedata'
               LIMIT ?""",
            groupNodeId,
            limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("embedding_text") ?: rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.GRAPH,
                dataType = rs.getString("data_type"),
                corpus = "gamedata",
            )
        }
    }
}
