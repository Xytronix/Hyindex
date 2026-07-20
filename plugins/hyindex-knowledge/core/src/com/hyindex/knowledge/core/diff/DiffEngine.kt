// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.diff

import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.time.Instant


class DiffEngine(
    private val log: LogProvider = StdoutLogProvider,
) {


    fun computeDiff(
        versionA: String,
        versionB: String,
        dbFileA: File,
        dbFileB: File,
        corpusFilter: String? = null,
        changeTypeFilter: String? = null,
        dataTypeFilter: String? = null,
        scopeFilter: String? = null,
        limit: Int = Int.MAX_VALUE,
    ): VersionDiff {
        val dbA = KnowledgeDatabase.forFile(dbFileA, log)
        val dbB = KnowledgeDatabase.forFile(dbFileB, log)
        try {
            val nodesA = loadNodeSummaries(dbA, corpusFilter, scopeFilter)
            val nodesB = loadNodeSummaries(dbB, corpusFilter, scopeFilter)
            val unchangedFiles = run {
                val hashesA = loadFileHashes(dbA)
                val hashesB = loadFileHashes(dbB)
                hashesA.entries.filter { (path, hash) -> hashesB[path] == hash }.map { it.key }.toSet()
            }
            return computeDiffFromSummaries(versionA, versionB, nodesA, nodesB, unchangedFiles,
                dbA, dbB, corpusFilter, changeTypeFilter, dataTypeFilter, scopeFilter, limit)
        } finally {
            dbA.close()
            dbB.close()
        }
    }


    fun computeDiff(
        versionA: String,
        versionB: String,
        sourceA: File,
        sourceB: File,
        isSnapshotA: Boolean,
        isSnapshotB: Boolean,
        corpusFilter: String? = null,
        changeTypeFilter: String? = null,
        dataTypeFilter: String? = null,
        scopeFilter: String? = null,
        limit: Int = Int.MAX_VALUE,
    ): VersionDiff {
        val dbA: KnowledgeDatabase? = if (!isSnapshotA) KnowledgeDatabase.forFile(sourceA, log) else null
        val dbB: KnowledgeDatabase? = if (!isSnapshotB) KnowledgeDatabase.forFile(sourceB, log) else null
        try {
            val rawA: Map<String, NodeSummary> = if (isSnapshotA) {
                loadSnapshot(sourceA).associateBy { it.id }
            } else {
                loadNodeSummaries(dbA!!, corpusFilter, scopeFilter)
            }
            val rawB: Map<String, NodeSummary> = if (isSnapshotB) {
                loadSnapshot(sourceB).associateBy { it.id }
            } else {
                loadNodeSummaries(dbB!!, corpusFilter, scopeFilter)
            }

            val nodesA = if (isSnapshotA) rawA.filterSummaries(corpusFilter, scopeFilter) else rawA
            val nodesB = if (isSnapshotB) rawB.filterSummaries(corpusFilter, scopeFilter) else rawB

            val unchangedFiles: Set<String> = if (dbA != null && dbB != null) {
                val hA = loadFileHashes(dbA)
                val hB = loadFileHashes(dbB)
                hA.entries.filter { (p, h) -> hB[p] == h }.map { it.key }.toSet()
            } else emptySet()
            return computeDiffFromSummaries(versionA, versionB, nodesA, nodesB, unchangedFiles,
                dbA, dbB, corpusFilter, changeTypeFilter, dataTypeFilter, scopeFilter, limit)
        } finally {
            dbA?.close()
            dbB?.close()
        }
    }

    private fun Map<String, NodeSummary>.filterSummaries(
        corpusFilter: String?,
        scopeFilter: String?,
    ): Map<String, NodeSummary> {
        var seq = values.asSequence()
        if (corpusFilter != null) seq = seq.filter { it.corpus == corpusFilter }
        if (scopeFilter != null) {
            val lower = scopeFilter.lowercase()
            seq = seq.filter {
                it.displayName.lowercase().contains(lower) ||
                it.id.lowercase().contains(lower) ||
                (it.filePath?.lowercase()?.contains(lower) == true)
            }
        }
        return seq.associateBy { it.id }
    }

    private fun computeDiffFromSummaries(
        versionA: String,
        versionB: String,
        nodesA: Map<String, NodeSummary>,
        nodesB: Map<String, NodeSummary>,
        unchangedFiles: Set<String>,
        dbA: KnowledgeDatabase?,
        dbB: KnowledgeDatabase?,
        @Suppress("UNUSED_PARAMETER") corpusFilter: String?,
        changeTypeFilter: String?,
        dataTypeFilter: String?,
        @Suppress("UNUSED_PARAMETER") scopeFilter: String?,
        limit: Int,
    ): VersionDiff {
        val nodeIdsA = nodesA.keys
        val nodeIdsB = nodesB.keys


        val corporaA = nodesA.values.map { it.corpus }.toSet()
        val corporaB = nodesB.values.map { it.corpus }.toSet()
        val skippedCorpora = mutableMapOf<String, String>()
        for (corpus in (corporaB - corporaA)) {
            val count = nodesB.values.count { it.corpus == corpus }
            skippedCorpora[corpus] = "not indexed in ${versionA.substringBefore("_")} ($count nodes only in ${versionB.substringBefore("_")})"
            log.info("Skipping corpus '$corpus': not present in version A ($versionA)")
        }
        for (corpus in (corporaA - corporaB)) {
            val count = nodesA.values.count { it.corpus == corpus }
            skippedCorpora[corpus] = "not indexed in ${versionB.substringBefore("_")} ($count nodes only in ${versionA.substringBefore("_")})"
            log.info("Skipping corpus '$corpus': not present in version B ($versionB)")
        }

        val entries = mutableListOf<DiffEntry>()


        for (id in nodeIdsB - nodeIdsA) {
            val node = nodesB[id] ?: continue
            if (node.corpus in skippedCorpora) continue
            entries.add(DiffEntry(nodeId = id, displayName = node.displayName, corpus = node.corpus,
                dataType = node.dataType, nodeType = node.nodeType, changeType = ChangeType.ADDED, filePath = node.filePath))
        }


        for (id in nodeIdsA - nodeIdsB) {
            val node = nodesA[id] ?: continue
            if (node.corpus in skippedCorpora) continue
            entries.add(DiffEntry(nodeId = id, displayName = node.displayName, corpus = node.corpus,
                dataType = node.dataType, nodeType = node.nodeType, changeType = ChangeType.REMOVED, filePath = node.filePath))
        }


        for (id in nodeIdsA.intersect(nodeIdsB)) {
            val nodeA = nodesA[id] ?: continue
            val nodeB = nodesB[id] ?: continue
            if (nodeA.corpus in skippedCorpora) continue
            val owningFile = nodeA.owningFile
            if (owningFile != null && owningFile in unchangedFiles) continue
            if (nodeA.contentHash == nodeB.contentHash) continue
            val detail = if (dbA != null && dbB != null) computeDetail(nodeA, nodeB, dbA, dbB) else null
            entries.add(DiffEntry(nodeId = id, displayName = nodeB.displayName, corpus = nodeB.corpus,
                dataType = nodeB.dataType, nodeType = nodeB.nodeType, changeType = ChangeType.CHANGED,
                filePath = nodeB.filePath, detail = detail))
        }


        val enrichedEntries = entries.map { entry ->
            entry.copy(significance = computeSignificance(entry))
        }


        val withCrossRefs = if (dbB != null) {
            enrichCrossRefs(enrichedEntries, dbB)
        } else {
            enrichedEntries
        }


        var filtered = withCrossRefs.asSequence()
        if (changeTypeFilter != null) {
            val ct = runCatching { ChangeType.valueOf(changeTypeFilter.uppercase()) }.getOrNull()
            if (ct != null) filtered = filtered.filter { it.changeType == ct }
        }
        if (dataTypeFilter != null) filtered = filtered.filter { it.dataType == dataTypeFilter }

        val finalEntries = filtered.take(limit).toList()


        val patchNotes = if (dbB != null) {
            computePatchNoteCorrelation(finalEntries, dbB)
        } else {
            emptyList()
        }

        return VersionDiff(
            versionA = versionA,
            versionB = versionB,
            computedAt = Instant.now().toString(),
            summary = buildSummary(entries, skippedCorpora),
            entries = finalEntries,
            relatedPatchNotes = patchNotes,
        )
    }

    @Serializable
    data class NodeSummary(
        val id: String,
        val displayName: String,
        val corpus: String,
        val dataType: String?,
        val nodeType: String,
        val filePath: String?,
        val owningFile: String?,
        val contentHash: String?,
    )

    private val snapshotJson = Json { prettyPrint = true }


    fun loadNodeSummariesFromDb(dbFile: File, corpusFilter: String? = null, scopeFilter: String? = null): Map<String, NodeSummary> {
        val db = KnowledgeDatabase.forFile(dbFile, log)
        return try {
            loadNodeSummaries(db, corpusFilter, scopeFilter)
        } finally {
            db.close()
        }
    }


    fun writeSnapshot(dbFile: File, outFile: File) {
        val summaries = loadNodeSummariesFromDb(dbFile)
        outFile.parentFile?.mkdirs()
        outFile.writeText(snapshotJson.encodeToString(summaries.values.toList()))
    }


    fun loadSnapshot(file: File): List<NodeSummary> {
        return snapshotJson.decodeFromString(file.readText())
    }

    private fun loadNodeSummaries(
        db: KnowledgeDatabase,
        corpusFilter: String?,
        scopeFilter: String? = null,
    ): Map<String, NodeSummary> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        if (corpusFilter != null) {
            conditions.add("corpus = ?")
            params.add(corpusFilter)
        }
        if (scopeFilter != null) {
            val like = "%$scopeFilter%"
            conditions.add("(display_name LIKE ? OR id LIKE ? OR file_path LIKE ?)")
            params.add(like); params.add(like); params.add(like)
        }

        val sql = buildString {
            append("SELECT id, display_name, corpus, data_type, node_type, file_path, owning_file, content FROM nodes")
            if (conditions.isNotEmpty()) append(" WHERE ${conditions.joinToString(" AND ")}")
        }
        return db.query(sql, *params.toTypedArray()) { rs ->
            val content = rs.getString("content") ?: ""
            NodeSummary(
                id = rs.getString("id"),
                displayName = rs.getString("display_name"),
                corpus = rs.getString("corpus"),
                dataType = rs.getString("data_type"),
                nodeType = rs.getString("node_type"),
                filePath = rs.getString("file_path"),
                owningFile = rs.getString("owning_file"),
                contentHash = content.hashCode().toString(),
            )
        }.associateBy { it.id }
    }

    private fun loadFileHashes(db: KnowledgeDatabase): Map<String, String> {
        return db.query("SELECT file_path, file_hash FROM file_hashes") { rs ->
            rs.getString("file_path") to rs.getString("file_hash")
        }.toMap()
    }

    private fun computeDetail(
        nodeA: NodeSummary,
        nodeB: NodeSummary,
        dbA: KnowledgeDatabase,
        dbB: KnowledgeDatabase,
    ): DiffDetail? {
        return when (nodeA.corpus) {
            "code" -> computeCodeDetail(nodeA, nodeB, dbA, dbB)
            "gamedata" -> computeGameDataDetail(nodeA, nodeB, dbA, dbB)
            "client" -> DiffDetail.Client(contentChanged = true)
            "docs" -> computeDocsDetail(nodeA, nodeB, dbA, dbB)
            else -> null
        }
    }

    private fun computeDocsDetail(
        nodeA: NodeSummary,
        nodeB: NodeSummary,
        dbA: KnowledgeDatabase,
        dbB: KnowledgeDatabase,
    ): DiffDetail.Docs {
        val contentA = loadContent(dbA, nodeA.id)
        val contentB = loadContent(dbB, nodeB.id)
        val dateA = loadPublishedDate(dbA, nodeA.id)
        val dateB = loadPublishedDate(dbB, nodeB.id)
        val titleChanged = nodeA.displayName != nodeB.displayName

        return DiffDetail.Docs(
            titleChanged = titleChanged,
            oldTitle = if (titleChanged) nodeA.displayName else null,
            newTitle = if (titleChanged) nodeB.displayName else null,
            bodyChanged = contentA != contentB,
            oldPublishedDate = dateA,
            newPublishedDate = dateB,
        )
    }

    private fun loadPublishedDate(db: KnowledgeDatabase, nodeId: String): String? {
        return db.query("SELECT published_date FROM nodes WHERE id = ?", nodeId) {
            it.getString("published_date")
        }.firstOrNull()
    }

    private fun computeCodeDetail(
        nodeA: NodeSummary,
        nodeB: NodeSummary,
        dbA: KnowledgeDatabase,
        dbB: KnowledgeDatabase,
    ): DiffDetail.Code {
        val contentA = loadContent(dbA, nodeA.id)
        val contentB = loadContent(dbB, nodeB.id)

        val sigA = extractSignature(contentA)
        val sigB = extractSignature(contentB)

        return DiffDetail.Code(
            signatureChanged = sigA != sigB,
            oldSignature = if (sigA != sigB) sigA else null,
            newSignature = if (sigA != sigB) sigB else null,
            bodyChanged = contentA != contentB,
        )
    }

    private fun computeGameDataDetail(
        nodeA: NodeSummary,
        nodeB: NodeSummary,
        dbA: KnowledgeDatabase,
        dbB: KnowledgeDatabase,
    ): DiffDetail.GameData {
        val contentA = loadContent(dbA, nodeA.id)
        val contentB = loadContent(dbB, nodeB.id)

        val fieldsA = JsonDiffer.flatten(contentA)
        val fieldsB = JsonDiffer.flatten(contentB)
        val fieldChanges = JsonDiffer.diffFlat(fieldsA, fieldsB)

        return DiffDetail.GameData(fieldChanges = fieldChanges)
    }

    private fun loadContent(db: KnowledgeDatabase, nodeId: String): String {
        return db.query("SELECT content FROM nodes WHERE id = ?", nodeId) {
            it.getString("content") ?: ""
        }.firstOrNull() ?: ""
    }

    private fun extractSignature(content: String): String? {

        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("//") && !it.startsWith("@") && !it.startsWith("*") && !it.startsWith("/*") }
            .firstOrNull()
    }

    private fun computeSignificance(entry: DiffEntry): Significance {
        val nameAndPath = "${entry.nodeId} ${entry.filePath ?: ""} ${entry.displayName}".lowercase()
        val isProtocolOrPacket = nameAndPath.contains("protocol") || nameAndPath.contains("packet")

        if (isProtocolOrPacket) return Significance.HIGH

        if (entry.corpus == "gamedata" && entry.changeType in listOf(ChangeType.ADDED, ChangeType.REMOVED)) {
            return Significance.HIGH
        }

        val detail = entry.detail
        if (entry.corpus == "code" && detail is DiffDetail.Code && detail.signatureChanged) {
            return Significance.HIGH
        }

        if (entry.corpus == "code" && detail is DiffDetail.Code && detail.bodyChanged && !detail.signatureChanged) {
            return Significance.MEDIUM
        }
        if (entry.corpus == "gamedata" && entry.changeType == ChangeType.CHANGED &&
            detail is DiffDetail.GameData && detail.fieldChanges.isNotEmpty()) {
            return Significance.MEDIUM
        }
        if (entry.corpus == "client") {
            return Significance.MEDIUM
        }

        return Significance.LOW
    }

    private fun enrichCrossRefs(entries: List<DiffEntry>, dbB: KnowledgeDatabase): List<DiffEntry> {
        val gamedataCandidates = entries
            .filter { it.corpus == "gamedata" && it.changeType in listOf(ChangeType.ADDED, ChangeType.CHANGED) }
            .take(30)
            .map { it.nodeId }
            .toSet()

        if (gamedataCandidates.isEmpty()) return entries


        val crossRefMap = mutableMapOf<String, List<CrossRef>>()
        for (nodeId in gamedataCandidates) {
            val refs = dbB.query(
                """
                SELECT n.id, n.display_name, n.corpus, e.edge_type
                FROM edges e
                JOIN nodes n ON (
                    CASE WHEN e.target_id = ? THEN e.source_id ELSE e.target_id END = n.id
                )
                WHERE (e.target_id = ? OR e.source_id = ?)
                  AND n.id != ?
                LIMIT 5
                """,
                nodeId, nodeId, nodeId, nodeId
            ) { rs ->
                CrossRef(
                    id = rs.getString("id"),
                    displayName = rs.getString("display_name"),
                    corpus = rs.getString("corpus") ?: "code",
                    edgeType = rs.getString("edge_type"),
                )
            }
            if (refs.isNotEmpty()) crossRefMap[nodeId] = refs
        }

        if (crossRefMap.isEmpty()) return entries

        return entries.map { entry ->
            val refs = crossRefMap[entry.nodeId]
            if (refs != null) entry.copy(crossRefs = refs) else entry
        }
    }

    private fun sanitizeLike(name: String): String =
        name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun computePatchNoteCorrelation(entries: List<DiffEntry>, dbB: KnowledgeDatabase): List<PatchNoteRef> {
        val highEntries = entries.filter { it.significance == Significance.HIGH }.take(10)
        val result = mutableListOf<PatchNoteRef>()
        for (entry in highEntries) {
            val name = entry.displayName
            if (name.length < 4) continue
            val safeName = sanitizeLike(name)
            val matches = dbB.query(
                """
                SELECT display_name FROM nodes
                WHERE corpus = 'docs' AND id LIKE 'blog:%'
                  AND (content LIKE ? ESCAPE '\' OR display_name LIKE ? ESCAPE '\')
                LIMIT 3
                """,
                "%$safeName%", "%$safeName%"
            ) { rs -> rs.getString("display_name") }
            for (title in matches) {
                result.add(PatchNoteRef(changedEntity = name, patchNoteTitle = title))
            }
        }
        return result
    }

    private fun buildSummary(entries: List<DiffEntry>, skippedCorpora: Map<String, String>): DiffSummary {
        val byCorpus = entries.groupBy { it.corpus }
        val corpusSummaries = byCorpus.mapValues { (_, entries) ->
            CorpusDiffSummary(
                added = entries.count { it.changeType == ChangeType.ADDED },
                removed = entries.count { it.changeType == ChangeType.REMOVED },
                changed = entries.count { it.changeType == ChangeType.CHANGED },
            )
        }

        return DiffSummary(
            totalAdded = entries.count { it.changeType == ChangeType.ADDED },
            totalRemoved = entries.count { it.changeType == ChangeType.REMOVED },
            totalChanged = entries.count { it.changeType == ChangeType.CHANGED },
            byCorpus = corpusSummaries,
            skippedCorpora = skippedCorpora,
        )
    }
}
