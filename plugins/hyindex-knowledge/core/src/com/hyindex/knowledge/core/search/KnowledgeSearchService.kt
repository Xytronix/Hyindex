// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.embedding.Reranker
import com.hyindex.knowledge.core.embedding.VoyageReranker
import com.hyindex.knowledge.core.index.CorpusIndexManager
import com.hyindex.knowledge.core.logging.LogProvider
import com.hyindex.knowledge.core.logging.StdoutLogProvider
import kotlinx.coroutines.runBlocking

class KnowledgeSearchService(
    private var db: KnowledgeDatabase,
    private var indexManager: CorpusIndexManager,
    private val log: LogProvider = StdoutLogProvider,
    private val config: KnowledgeConfig = KnowledgeConfig(),
    reranker: Reranker? = null,
) {

    private val reranker: Reranker? = reranker ?: if (config.rerankerEnabled) {
        VoyageReranker(config.embeddingApiKey, config.rerankerModel, log, config.embeddingBaseUrl)
    } else null

    internal fun maybeRerank(query: String, results: List<SearchResult>): List<SearchResult> {
        val active = reranker ?: return results
        if (!config.rerankerEnabled || results.size < 2) return results

        val candidateCount = minOf(config.rerankerTopN, results.size)
        val candidates = results.take(candidateCount)
        val tail = results.drop(candidateCount)

        val order = active.rerank(query, candidates.map { it.snippet })
        if (order.isEmpty()) return results

        val reordered = order.mapIndexedNotNull { position, (index, relevance) ->
            candidates.getOrNull(index)?.copy(
                score = 1.0 - position / candidateCount.toDouble(),
                relevanceScore = relevance,
            )
        }
        return reordered + tail
    }


    fun reinitialize(newDb: KnowledgeDatabase, newIndexManager: CorpusIndexManager) {
        log.info("Reinitializing search service with new database and index manager")
        indexManager.closeAll()

        db = newDb
        indexManager = newIndexManager
    }


    fun search(query: String, limit: Int = 10): List<SearchResult> {
        val router = QueryRouter(db)
        val routeResult = router.route(query)

        return when (routeResult.strategy) {
            QueryStrategy.GRAPH -> graphSearch(query, routeResult, db, limit)
            QueryStrategy.VECTOR -> vectorSearch(query, db, limit)
            QueryStrategy.HYBRID -> hybridSearch(query, routeResult, db, limit)
        }
    }

    fun searchCode(
        query: String,
        classFilter: String? = null,
        limit: Int = 10,
        rerank: Boolean = true,
        pathPrefix: String? = null,
        classExact: Boolean = false,
        pathExact: Boolean = false,
        visibility: String? = null,
        annotation: String? = null,
    ): List<SearchResult> {
        val widen = !pathPrefix.isNullOrBlank()
        val nameLimit = if (widen) limit * 5 else limit
        val semanticLimit = if (widen) limit * 5 else limit * 2
        val nameHits = searchByName(query, nameLimit)
        val semantic = try {
            search(query, semanticLimit)
        } catch (e: Exception) {
            log.warn("Code search (semantic) failed; returning lexical matches only: ${e.message}")
            emptyList()
        }
        val combined = if (config.hybridEnabled) {
            rrfFuse(listOf(nameHits, semantic, lexicalSearch(query, Corpus.CODE, config.hybridLexicalLimit)), config.hybridRrfK)
        } else {
            deduplicateResults(nameHits + semantic)
        }
        val classFiltered = if (classFilter == null) combined else combined.filter { result ->
            if (classExact) classNameMatchesExact(result.nodeId, classFilter)
            else result.displayName.contains(classFilter, ignoreCase = true) ||
                result.filePath.contains(classFilter, ignoreCase = true)
        }
        val pathFiltered = if (!widen) classFiltered else classFiltered.filter { result ->
            if (pathExact) pathMatchesExact(result.filePath, pathPrefix!!)
            else result.filePath.contains(pathPrefix!!, ignoreCase = true) ||
                result.nodeId.contains(pathPrefix, ignoreCase = true)
        }
        val filtered = filterByFacets(pathFiltered, visibility, annotation)
        val ranked = if (rerank && config.rerankerEnabled) maybeRerank(query, filtered) else filtered
        return diversifyResults(ranked).take(limit)
    }

    internal fun filterByFacets(
        results: List<SearchResult>,
        visibility: String?,
        annotation: String?,
    ): List<SearchResult> {
        if (visibility.isNullOrBlank() && annotation.isNullOrBlank()) return results
        if (results.isEmpty()) return results
        val metadata = metadataFor(results.map { it.nodeId })
        return results.filter { result ->
            val meta = metadata[result.nodeId] ?: return@filter true
            val visibilityOk = visibility.isNullOrBlank() || metadataVisibility(meta).equals(visibility, ignoreCase = true)
            val annotationOk = annotation.isNullOrBlank() || metadataAnnotations(meta).any { it.equals(annotation, ignoreCase = true) }
            visibilityOk && annotationOk
        }
    }

    private fun metadataFor(nodeIds: List<String>): Map<String, String?> {
        if (nodeIds.isEmpty()) return emptyMap()
        val placeholders = nodeIds.joinToString(",") { "?" }
        return db.query(
            "SELECT id, metadata FROM nodes WHERE id IN ($placeholders)",
            *nodeIds.toTypedArray(),
        ) { rs -> rs.getString("id") to rs.getString("metadata") }.toMap()
    }

    internal fun metadataVisibility(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        return Regex("\"visibility\"\\s*:\\s*\"([^\"]*)\"").find(metadata)?.groupValues?.get(1)
    }

    internal fun metadataAnnotations(metadata: String?): List<String> {
        if (metadata.isNullOrBlank()) return emptyList()
        val arr = Regex("\"annotations\"\\s*:\\s*\\[([^\\]]*)\\]").find(metadata)?.groupValues?.get(1) ?: return emptyList()
        return Regex("\"([^\"]+)\"").findAll(arr).map { it.groupValues[1] }.toList()
    }

    internal fun classNameMatchesExact(nodeId: String, classFilter: String): Boolean {
        val fqcn = nodeId.substringBefore('#')
        val simple = fqcn.substringAfterLast('.')
        return simple == classFilter || fqcn == classFilter
    }

    internal fun pathMatchesExact(filePath: String, value: String): Boolean {
        if (filePath.substringAfterLast('/') == value) return true
        val at = filePath.indexOf("/$value")
        if (at < 0) return false
        val end = at + value.length + 1
        return end == filePath.length || filePath[end] == '/'
    }

    internal fun filterBySnippet(results: List<SearchResult>, snippetContains: String?): List<SearchResult> {
        if (snippetContains.isNullOrBlank()) return results
        return results.filter { it.snippet.contains(snippetContains, ignoreCase = true) }
    }

    internal fun filterByDataTypes(results: List<SearchResult>, dataTypeFilters: Set<String>?): List<SearchResult> {
        if (dataTypeFilters == null) return results
        return results.filter { it.dataType in dataTypeFilters }
    }


    fun searchByName(name: String, limit: Int = 10): List<SearchResult> {
        val q = name.trim()
        if (q.isEmpty()) return emptyList()
        return db.query(
            """SELECT id, display_name, content, file_path, line_start, metadata
               FROM nodes
               WHERE corpus = 'code' AND (display_name = ? OR display_name LIKE ?)
               ORDER BY CASE WHEN display_name = ? THEN 0 ELSE 1 END, length(display_name)
               LIMIT ?""",
            q, "%#$q", q, limit,
        ) { rs ->
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = rs.getString("content") ?: "",
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = 1.0,
                source = ResultSource.LEXICAL,
            )
        }
    }

    internal fun metadataIsThin(metadata: String?): Boolean {
        if (metadata.isNullOrBlank()) return false
        return Regex("\"thin\"\\s*:\\s*true").containsMatchIn(metadata)
    }

    fun vectorSearch(query: String, db: KnowledgeDatabase, limit: Int): List<SearchResult> {
        val hnsw = indexManager.getIndex(Corpus.CODE) ?: return emptyList()
        val provider = indexManager.getProvider(Corpus.CODE)

        val queryVec = runBlocking { provider.embedQuery(query) }
        val results = hnsw.query(queryVec, limit)

        return results.mapNotNull { (ordinal, score) ->
            nodeFromChunkIndex(db, ordinal, score.toDouble(), ResultSource.VECTOR, Corpus.CODE)
        }
    }


    fun searchCorpus(
        query: String,
        corpus: Corpus,
        limit: Int = 10,
        dataTypeFilter: String? = null,
        rerank: Boolean = true,
        snippetContains: String? = null,
        sort: String? = null,
    ): List<SearchResult> {
        val filters: Set<String>? = dataTypeFilter?.let { setOf(it) }
        return searchCorpusFiltered(query, corpus, limit, filters, rerank, snippetContains, sort)
    }

    fun searchCorpus(
        query: String,
        corpus: Corpus,
        limit: Int = 10,
        dataTypeFilters: Set<String>?,
        rerank: Boolean = true,
        snippetContains: String? = null,
        sort: String? = null,
    ): List<SearchResult> = searchCorpusFiltered(query, corpus, limit, dataTypeFilters, rerank, snippetContains, sort)

    private fun searchCorpusFiltered(
        query: String,
        corpus: Corpus,
        limit: Int,
        dataTypeFilters: Set<String>?,
        rerank: Boolean = true,
        snippetContains: String? = null,
        sort: String? = null,
    ): List<SearchResult> {
        if (corpus == Corpus.CODE) return searchCode(query, dataTypeFilters?.firstOrNull(), limit, rerank)

        val hnsw = indexManager.getIndex(corpus) ?: return emptyList()
        val provider = indexManager.getProvider(corpus)

        val recency = sort.equals("recency", ignoreCase = true)
        val queryVec = runBlocking { provider.embedQuery(query) }
        val fetchLimit = if (corpus == Corpus.GAMEDATA) maxOf(limit * 5, config.gamedataFetchLimit) else if (dataTypeFilters != null || !snippetContains.isNullOrBlank() || recency) limit * 5 else limit
        val results = hnsw.query(queryVec, fetchLimit)

        val mapped = results.mapNotNull { (ordinal, score) ->
            nodeFromChunkIndex(db, ordinal, score.toDouble(), ResultSource.VECTOR, corpus)
        }

        val fused = if (config.hybridEnabled) {
            rrfFuse(listOf(mapped, lexicalSearch(query, corpus, config.hybridLexicalLimit)), config.hybridRrfK)
        } else mapped

        val adjusted = applyCorpusPenalty(fused, corpus)
        val typeFiltered = filterByDataTypes(adjusted, dataTypeFilters)
        val filtered = filterBySnippet(typeFiltered, snippetContains)
        val ranked = if (rerank && config.rerankerEnabled) maybeRerank(query, filtered) else filtered
        val sorted = if (recency) sortByRecency(ranked) else ranked
        return sorted.take(limit)
    }

    internal fun sanitizeFtsQuery(query: String): String {
        val terms = Regex("[A-Za-z0-9]+").findAll(query).map { it.value }.toList()
        if (terms.isEmpty()) return ""
        return terms.joinToString(" OR ") { "\"$it\"" }
    }

    internal fun lexicalSearch(query: String, corpus: Corpus, limit: Int): List<SearchResult> {
        val match = sanitizeFtsQuery(query)
        if (match.isBlank()) return emptyList()
        return try {
            db.query(
                """SELECT nodes.id AS id, nodes.display_name AS display_name, nodes.content AS content,
                          nodes.embedding_text AS embedding_text, nodes.file_path AS file_path,
                          nodes.line_start AS line_start, nodes.data_type AS data_type
                   FROM nodes_fts
                   JOIN nodes ON nodes.id = nodes_fts.node_id
                   WHERE nodes_fts MATCH ? AND nodes_fts.corpus = ?
                   ORDER BY bm25(nodes_fts, 0.0, 0.0, ${config.hybridNameWeight}, ${config.hybridBodyWeight})
                   LIMIT ?""",
                match, corpus.id, limit,
            ) { rs ->
                val snippet = if (corpus != Corpus.CODE) {
                    rs.getString("embedding_text") ?: rs.getString("content") ?: ""
                } else {
                    rs.getString("content") ?: ""
                }
                SearchResult(
                    nodeId = rs.getString("id"),
                    displayName = rs.getString("display_name"),
                    snippet = snippet,
                    filePath = rs.getString("file_path") ?: "",
                    lineStart = rs.getInt("line_start"),
                    score = 0.0,
                    source = ResultSource.LEXICAL,
                    dataType = rs.getString("data_type"),
                    corpus = corpus.id,
                )
            }
        } catch (e: Exception) {
            log.warn("Lexical FTS search failed for corpus ${corpus.id}; falling back to vector-only: ${e.message}")
            emptyList()
        }
    }

    internal fun sortByRecency(results: List<SearchResult>): List<SearchResult> {
        if (results.isEmpty()) return results
        val dates = publishedDatesFor(results.map { it.nodeId })
        return results.sortedWith(
            compareByDescending<SearchResult> { dates[it.nodeId] ?: "" }
                .thenByDescending { it.score }
        )
    }

    private fun publishedDatesFor(nodeIds: List<String>): Map<String, String?> {
        if (nodeIds.isEmpty()) return emptyMap()
        val placeholders = nodeIds.joinToString(",") { "?" }
        return db.query(
            "SELECT id, published_date FROM nodes WHERE id IN ($placeholders)",
            *nodeIds.toTypedArray(),
        ) { rs -> rs.getString("id") to rs.getString("published_date") }.toMap()
    }

    internal fun rrfFuse(lists: List<List<SearchResult>>, k: Int): List<SearchResult> {
        val scores = linkedMapOf<String, Double>()
        val best = linkedMapOf<String, SearchResult>()
        for (list in lists) {
            for ((rank, result) in list.withIndex()) {
                scores[result.nodeId] = (scores[result.nodeId] ?: 0.0) + 1.0 / (k + rank + 1)
                if (result.nodeId !in best) best[result.nodeId] = result
            }
        }
        return best.values
            .map { it.copy(score = scores[it.nodeId] ?: 0.0) }
            .sortedByDescending { it.score }
    }

    internal fun applyCorpusPenalty(mapped: List<SearchResult>, corpus: Corpus): List<SearchResult> =
        when (corpus) {
            Corpus.DOCS ->
                mapped.map { if (it.nodeId.startsWith("blog:")) it.copy(score = it.score * config.blogScorePenalty) else it }
                    .sortedByDescending { it.score }
            Corpus.GAMEDATA ->
                mapped.map { if (it.dataType in config.gamedataWorldNodeTypes) it.copy(score = it.score * config.gamedataWorldNodePenalty) else it }
                    .sortedByDescending { it.score }
            else -> mapped
        }


    fun searchWithExpansion(
        query: String,
        corpora: List<Corpus>,
        perCorpus: Int = 10,
        expansionLimit: Int = 5,
    ): List<SearchResult> {
        val gamedataTypeHint = detectGamedataIntent(query)
        val directResults = corpora.flatMap { corpus ->
            try {


                val results = if (corpus == Corpus.CODE) {
                    vectorSearch(query, db, perCorpus)
                } else {
                    val typeFilters = if (corpus == Corpus.GAMEDATA) gamedataTypeHint else null
                    searchCorpus(query, corpus, perCorpus, typeFilters, rerank = false)
                }
                if (corpus == Corpus.GAMEDATA && gamedataTypeHint == null) {
                    results.filter { it.score >= config.gamedataUnintentScoreFloor }
                } else {
                    results
                }
            } catch (e: Exception) {
                log.warn("Search failed for corpus ${corpus.id}: ${e.message}")
                emptyList()
            }
        }

        val expanded = expandCrossCorpus(directResults, corpora, expansionLimit)
            .filter { it.score >= config.minExpansionResultScore }
        log.info("Graph expansion: ${directResults.size} direct → ${expanded.size} expanded results")

        val seedToExpanded = expanded.groupBy { it.expandedFromNodeId ?: "" }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, results) -> results.map { it.nodeId } }

        val annotatedDirect = directResults.map { result ->
            val connections = seedToExpanded[result.nodeId]
            if (connections != null) result.copy(connectedNodeIds = connections)
            else result
        }

        return maybeRerank(query, rankCrossCorpus(annotatedDirect + expanded))
    }

    private companion object {
        private val GAMEDATA_INTENT_RULES = listOf(
            Regex("\\b(craft|recipe|crafting|bench|smelt|cook|brew)s?\\b", RegexOption.IGNORE_CASE) to setOf("recipe", "item"),
            Regex("\\b(drop|loot)s?\\s+from\\b", RegexOption.IGNORE_CASE) to setOf("drop", "npc"),
            Regex("\\b(npc|mob|creature|enem(?:y|ies)|trork|kweebec|feran)s?\\b", RegexOption.IGNORE_CASE) to setOf("npc", "npc_group"),
            Regex("\\b(block|ore|stone|wood|plank)s?\\b", RegexOption.IGNORE_CASE) to setOf("block"),
            Regex("\\b(farm|farming|crop|grow|plant|seed|harvest)s?\\b", RegexOption.IGNORE_CASE) to setOf("farming", "item"),
            Regex("\\b(shop|merchant|vendor|buy|sell|trade)s?\\b", RegexOption.IGNORE_CASE) to setOf("shop"),
            Regex("\\b(biome|zone|climate)s?\\b", RegexOption.IGNORE_CASE) to setOf("biome"),
            Regex("\\b(weather|rain|snow|storm)s?\\b", RegexOption.IGNORE_CASE) to setOf("weather"),
            Regex("\\b(objective|quest|mission|task|bount(?:y|ies))s?\\b", RegexOption.IGNORE_CASE) to setOf("objective"),
        )
    }

    internal fun detectGamedataIntent(query: String): Set<String>? {
        val matched = mutableSetOf<String>()
        for ((pattern, dataTypes) in GAMEDATA_INTENT_RULES) {
            if (pattern.containsMatchIn(query)) matched.addAll(dataTypes)
        }
        return matched.ifEmpty { null }
    }

    private fun expandCrossCorpus(
        seeds: List<SearchResult>,
        enabledCorpora: List<Corpus>,
        limit: Int,
    ): List<SearchResult> {
        val traversal = GraphTraversal(db)
        val expanded = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        val codeEnabled = enabledCorpora.any { it == Corpus.CODE }
        val gamedataEnabled = enabledCorpora.any { it == Corpus.GAMEDATA }
        val clientEnabled = enabledCorpora.any { it == Corpus.CLIENT }

        for (seed in seeds) {
            if (seed.nodeId in seen) continue
            seen.add(seed.nodeId)

            if (seed.score < config.minExpansionSeedScore) continue

            val seedLabel = seed.displayName
                .substringAfterLast('#')
                .substringAfterLast('.')

            var seedExpanded = 0

            when (seed.corpus) {
                "gamedata" -> {
                    if (codeEnabled) {
                        traversal.findImplementingCode(seed.nodeId, limit)
                            .filter { it.nodeId !in seen }
                            .take(config.perSeedExpansionCap - seedExpanded)
                            .forEach { result ->
                                seen.add(result.nodeId)
                                seedExpanded++
                                expanded.add(result.copy(
                                    score = seed.score * config.expansionDiscount,
                                    bridgedFrom = seedLabel,
                                    bridgeEdgeType = "IMPLEMENTED_BY",
                                    expandedFromNodeId = seed.nodeId,
                                ))
                            }
                    }
                    if (clientEnabled && seedExpanded < config.perSeedExpansionCap) {
                        traversal.findUIForGamedata(seed.nodeId, limit)
                            .filter { it.nodeId !in seen }
                            .take(config.perSeedExpansionCap - seedExpanded)
                            .forEach { result ->
                                seen.add(result.nodeId)
                                seedExpanded++
                                expanded.add(result.copy(
                                    score = seed.score * config.expansionDiscount,
                                    bridgedFrom = seedLabel,
                                    bridgeEdgeType = "UI_BINDS_TO",
                                    expandedFromNodeId = seed.nodeId,
                                ))
                            }
                    }
                }
                "code" -> if (gamedataEnabled) {
                    traversal.findGamedataForCode(seed.nodeId, limit)
                        .filter { it.nodeId !in seen }
                        .take(config.perSeedExpansionCap)
                        .forEach { result ->
                            seen.add(result.nodeId)
                            expanded.add(result.copy(
                                score = seed.score * config.expansionDiscount,
                                bridgedFrom = seedLabel,
                                bridgeEdgeType = "IMPLEMENTED_BY",
                                expandedFromNodeId = seed.nodeId,
                            ))
                        }
                }
                "client" -> if (gamedataEnabled) {
                    traversal.findUIBindings(seed.nodeId, limit)
                        .filter { it.nodeId !in seen }
                        .take(config.perSeedExpansionCap)
                        .forEach { result ->
                            seen.add(result.nodeId)
                            expanded.add(result.copy(
                                score = seed.score * config.expansionDiscount,
                                bridgedFrom = seedLabel,
                                bridgeEdgeType = "UI_BINDS_TO",
                                expandedFromNodeId = seed.nodeId,
                            ))
                        }
                }
                "docs" -> {
                    traversal.findDocsReferences(seed.nodeId, limit)
                        .filter { it.nodeId !in seen }
                        .filter {
                            (it.corpus == "code" && codeEnabled) ||
                            (it.corpus == "gamedata" && gamedataEnabled)
                        }
                        .take(config.perSeedExpansionCap)
                        .forEach { result ->
                            seen.add(result.nodeId)
                            expanded.add(result.copy(
                                score = seed.score * config.expansionDiscount,
                                bridgedFrom = seedLabel,
                                bridgeEdgeType = "DOCS_REFERENCES",
                                expandedFromNodeId = seed.nodeId,
                            ))
                        }
                }
            }
        }
        if (expanded.isNotEmpty()) {
            log.info("Graph expansion found ${expanded.size} cross-corpus results from ${seen.size} seeds")
        }
        return expanded
    }

    internal fun deduplicateResults(results: List<SearchResult>): List<SearchResult> {
        val best = linkedMapOf<String, SearchResult>()
        for (result in results) {
            val existing = best[result.nodeId]
            if (existing == null || result.score > existing.score) {
                val merged = if (existing != null) {
                    result.copy(
                        bridgedFrom = result.bridgedFrom ?: existing.bridgedFrom,
                        bridgeEdgeType = result.bridgeEdgeType ?: existing.bridgeEdgeType,
                        connectedNodeIds = result.connectedNodeIds.ifEmpty { existing.connectedNodeIds },
                    )
                } else result
                best[result.nodeId] = merged
            } else {
                val merged = existing.copy(
                    bridgedFrom = existing.bridgedFrom ?: result.bridgedFrom,
                    bridgeEdgeType = existing.bridgeEdgeType ?: result.bridgeEdgeType,
                    connectedNodeIds = existing.connectedNodeIds.ifEmpty { result.connectedNodeIds },
                )
                best[result.nodeId] = merged
            }
        }
        return best.values.toList()
    }


    internal fun rankCrossCorpus(results: List<SearchResult>): List<SearchResult> =
        diversifyResults(deduplicateResults(results).sortedByDescending { it.score })

    internal fun diversifyResults(results: List<SearchResult>): List<SearchResult> {
        if ((config.nearDupPenalty >= 1.0 && config.delegatePenalty >= 1.0) || results.size < 2) return results
        val thin = if (config.delegatePenalty < 1.0) {
            metadataFor(results.map { it.nodeId }).filterValues { metadataIsThin(it) }.keys
        } else emptySet()
        val nearDupActive = config.nearDupPenalty < 1.0
        val ordered = results.sortedByDescending { it.score }
        val kept = mutableListOf<SearchResult>()
        val out = mutableListOf<SearchResult>()
        for (candidate in ordered) {
            val name = simpleMethodName(candidate)
            val duplicate = nearDupActive && name != null && run {
                val tokens = snippetTokens(candidate.snippet)
                kept.any { other ->
                    name == simpleMethodName(other) ||
                        jaccard(tokens, snippetTokens(other.snippet)) >= config.nearDupJaccard
                }
            }
            if (name != null && !duplicate) kept.add(candidate)
            var score = candidate.score
            if (duplicate) score *= config.nearDupPenalty
            if (candidate.nodeId in thin) score *= config.delegatePenalty
            out.add(if (score == candidate.score) candidate else candidate.copy(score = score))
        }
        return out.sortedByDescending { it.score }
    }

    private fun simpleMethodName(result: SearchResult): String? {
        val id = result.displayName.takeIf { '#' in it } ?: result.nodeId.takeIf { '#' in it } ?: return null
        return id.substringAfterLast('#')
    }

    private fun snippetTokens(snippet: String): Set<String> =
        Regex("[A-Za-z0-9_]+").findAll(snippet).map { it.value }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        return intersection.toDouble() / (a.size + b.size - intersection)
    }


    fun getStats(): IndexStats = getCorpusStats(Corpus.CODE)

    fun getCorpusStats(corpus: Corpus): IndexStats {
        val nodeCount = db.query(
            "SELECT COUNT(*) FROM nodes WHERE corpus = ?", corpus.id
        ) { it.getInt(1) }.firstOrNull() ?: 0

        val typeBreakdown = db.query(
            "SELECT COALESCE(data_type, node_type) AS t, COUNT(*) FROM nodes WHERE corpus = ? GROUP BY t",
            corpus.id,
        ) { rs -> rs.getString(1) to rs.getInt(2) }.toMap()

        val edgeCount = db.query(
            "SELECT COUNT(*) FROM edges WHERE source_id IN (SELECT id FROM nodes WHERE corpus = ?)",
            corpus.id,
        ) { it.getInt(1) }.firstOrNull() ?: 0

        val vectorIndexLoaded = indexManager.getIndex(corpus)?.isLoaded() == true

        return IndexStats(
            corpus = corpus.id,
            nodeCount = nodeCount,
            typeBreakdown = typeBreakdown,
            edgeCount = edgeCount,
            vectorIndexLoaded = vectorIndexLoaded,
        )
    }


    private fun graphSearch(
        query: String,
        routeResult: RouteResult,
        db: KnowledgeDatabase,
        limit: Int,
    ): List<SearchResult> {
        val traversal = GraphTraversal(db)
        if (routeResult.entityName != null && routeResult.relation != null) {
            val nodeIds = db.query(
                "SELECT id FROM nodes WHERE LOWER(display_name) = LOWER(?) AND corpus = 'gamedata' LIMIT 5",
                routeResult.entityName,
            ) { it.getString("id") }
            val primaryId = nodeIds.firstOrNull()
            if (routeResult.relation == "CALLS") {
                val codeIds = db.query(
                    """SELECT id FROM nodes WHERE corpus = 'code'
                         AND (display_name = ? OR display_name LIKE ? OR display_name LIKE ?)
                       LIMIT 5""",
                    routeResult.entityName,
                    "${routeResult.entityName}#%",
                    "%.${routeResult.entityName}",
                ) { it.getString("id") }
                val codeId = codeIds.firstOrNull()
                return if (codeId != null) traversal.findCallers(codeId, limit) else emptyList()
            }
            val gamedataResult = when (routeResult.relation) {
                "REQUIRES_ITEM" -> if (primaryId != null) traversal.findRecipeInputs(primaryId, limit) else emptyList()
                "DROPS_ON_DEATH" -> if (primaryId != null) traversal.findDropsFrom(primaryId, limit) else emptyList()
                "OFFERED_IN_SHOP" -> if (primaryId != null) traversal.findShopsSellingItem(primaryId, limit) else emptyList()
                "HAS_MEMBER" -> if (primaryId != null) traversal.findGroupMembers(primaryId, limit) else emptyList()
                "UI_BINDS_TO" -> if (primaryId != null) traversal.findUIForGamedata(primaryId, limit) else emptyList()
                else -> null
            }
            if (gamedataResult != null) return gamedataResult
            return traversal.findByRelation(routeResult.entityName, routeResult.relation, limit)
        }
        return if (routeResult.entityName != null) {
            traversal.findByName(routeResult.entityName, limit)
        } else {
            vectorSearch(query, db, limit)
        }
    }

    private fun hybridSearch(
        query: String,
        routeResult: RouteResult,
        db: KnowledgeDatabase,
        limit: Int,
    ): List<SearchResult> {
        val vectorResults = vectorSearch(query, db, limit)
        val graphResults = graphSearch(query, routeResult, db, limit)
        return HybridScorer.mergeRRF(vectorResults, graphResults, limit = limit)
    }

    private fun nodeFromChunkIndex(
        db: KnowledgeDatabase,
        chunkIndex: Int,
        score: Double,
        source: ResultSource,
        corpus: Corpus,
    ): SearchResult? {
        val results = db.query(
            """SELECT id, display_name, content, embedding_text, file_path, line_start, data_type
               FROM nodes WHERE chunk_index = ? AND corpus = ? LIMIT 1""",
            chunkIndex, corpus.id,
        ) { rs ->
            val snippet = if (corpus != Corpus.CODE) {
                rs.getString("embedding_text")
                    ?: rs.getString("content") ?: ""
            } else {
                rs.getString("content") ?: ""
            }
            SearchResult(
                nodeId = rs.getString("id"),
                displayName = rs.getString("display_name"),
                snippet = snippet,
                filePath = rs.getString("file_path") ?: "",
                lineStart = rs.getInt("line_start"),
                score = score,
                source = source,
                dataType = rs.getString("data_type"),
                corpus = corpus.id,
            )
        }
        return results.firstOrNull()
    }


    fun lookupFilePaths(nameQuery: String, limit: Int = 20): List<FilePathResult> {
        val pattern = "%${nameQuery}%"
        return db.query(
            """
            SELECT DISTINCT display_name, file_path, node_type, corpus
            FROM nodes
            WHERE corpus = 'code'
              AND file_path IS NOT NULL
              AND (display_name LIKE ? OR file_path LIKE ?)
            ORDER BY
                CASE WHEN display_name LIKE ? THEN 0 ELSE 1 END,
                display_name
            LIMIT ?
            """.trimIndent(),
            pattern, pattern, pattern, limit,
        ) { rs ->
            FilePathResult(
                displayName = rs.getString("display_name"),
                filePath = rs.getString("file_path"),
                nodeType = rs.getString("node_type"),
            )
        }
    }

    fun resolveClassSourcePath(className: String): String? {
        val name = className.trim()
        if (name.isEmpty()) return null
        return if ('.' in name) {
            db.query(
                """SELECT owning_file FROM nodes
                   WHERE corpus = 'code' AND owning_file IS NOT NULL
                     AND (id = ? OR id LIKE ?)
                   LIMIT 1""",
                "class:$name", "$name#%",
            ) { it.getString("owning_file") }.firstOrNull()
        } else {
            db.query(
                """SELECT owning_file FROM nodes
                   WHERE corpus = 'code' AND owning_file IS NOT NULL
                     AND (display_name = ? OR display_name LIKE ?)
                   LIMIT 1""",
                name, "$name#%",
            ) { it.getString("owning_file") }.firstOrNull()
        }
    }

    fun resolveMethodSource(className: String?, methodName: String): MethodSourceResolution {
        val method = methodName.trim()
        if (method.isEmpty()) return MethodSourceResolution(emptyList())
        if ('#' in method) {
            val rows = db.query(
                """SELECT id, owning_file, line_start, line_end FROM nodes
                   WHERE corpus = 'code' AND owning_file IS NOT NULL
                     AND (display_name = ? OR id = ? OR id LIKE ?)""",
                method, method, "%.$method",
            ) { rs -> MethodSourceMatch(rs.getString("id"), rs.getString("owning_file"), rs.getInt("line_start"), rs.getInt("line_end")) }
            return MethodSourceResolution(rows.distinctBy { it.id })
        }
        val cls = className?.trim()?.takeIf { it.isNotEmpty() }
        val rows = if (cls != null) {
            if ('.' in cls) {
                db.query(
                    """SELECT id, owning_file, line_start, line_end FROM nodes
                       WHERE corpus = 'code' AND owning_file IS NOT NULL
                         AND (id = ? OR id LIKE ?)""",
                    "$cls#$method", "%.$cls#$method",
                ) { rs -> MethodSourceMatch(rs.getString("id"), rs.getString("owning_file"), rs.getInt("line_start"), rs.getInt("line_end")) }
            } else {
                db.query(
                    """SELECT id, owning_file, line_start, line_end FROM nodes
                       WHERE corpus = 'code' AND owning_file IS NOT NULL
                         AND (display_name = ? OR id LIKE ?)""",
                    "$cls#$method", "%.$cls#$method",
                ) { rs -> MethodSourceMatch(rs.getString("id"), rs.getString("owning_file"), rs.getInt("line_start"), rs.getInt("line_end")) }
            }
        } else {
            db.query(
                """SELECT id, owning_file, line_start, line_end FROM nodes
                   WHERE corpus = 'code' AND owning_file IS NOT NULL
                     AND (display_name LIKE ? OR id LIKE ?)""",
                "%#$method", "%#$method",
            ) { rs -> MethodSourceMatch(rs.getString("id"), rs.getString("owning_file"), rs.getInt("line_start"), rs.getInt("line_end")) }
        }
        val distinct = rows.distinctBy { it.id }
        return MethodSourceResolution(distinct)
    }

    fun resolveNodeId(raw: String): NodeIdResolution {
        val id = raw.trim()
        if (id.isEmpty()) return NodeIdResolution(null, emptyList())
        val exact = db.query(
            "SELECT id FROM nodes WHERE id = ? LIMIT 1", id,
        ) { it.getString("id") }
        if (exact.isNotEmpty()) return NodeIdResolution(id, emptyList())
        val refs = db.query(
            "SELECT DISTINCT id FROM nodes WHERE display_name = ? OR id LIKE ?",
            id, "%.$id",
        ) { it.getString("id") }
        return when (refs.size) {
            0 -> NodeIdResolution(null, emptyList())
            1 -> NodeIdResolution(refs.first(), emptyList())
            else -> NodeIdResolution(null, refs)
        }
    }

    fun getClassMethods(className: String): List<ClassMethod> {
        val startFile = resolveClassSourcePath(className) ?: return emptyList()
        val result = mutableListOf<ClassMethod>()
        val seenSignatures = mutableSetOf<String>()
        val visitedFiles = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Boolean>>()
        queue.add(startFile to true)
        while (queue.isNotEmpty()) {
            val (owningFile, isOwn) = queue.removeFirst()
            if (!visitedFiles.add(owningFile)) continue
            val fqcn = fqcnForOwningFile(owningFile)
            val declaringType = fqcn?.substringAfterLast('.') ?: ""
            val methods = db.query(
                """SELECT display_name, line_start, line_end, content FROM nodes
                   WHERE corpus = 'code' AND owning_file = ? AND node_type NOT IN ('JavaClass', 'Package', 'JavaFile', 'JavaType')
                   ORDER BY line_start""",
                owningFile,
            ) { rs ->
                ClassMethod(
                    name = rs.getString("display_name"),
                    lineStart = rs.getInt("line_start"),
                    lineEnd = rs.getInt("line_end"),
                    content = rs.getString("content") ?: "",
                    declaringType = declaringType,
                    inherited = !isOwn,
                )
            }
            for (method in methods) {
                val signature = method.name.substringAfterLast('#') + "(" + paramTypes(method.content) + ")"
                if (seenSignatures.add(signature)) result.add(method)
            }
            if (fqcn != null) {
                for (superFile in supertypeFiles(fqcn)) queue.add(superFile to false)
            }
        }
        return result
    }

    private fun fqcnForOwningFile(owningFile: String): String? {
        val classId = db.query(
            "SELECT id FROM nodes WHERE owning_file = ? AND node_type = 'JavaClass' LIMIT 1",
            owningFile,
        ) { it.getString("id") }.firstOrNull()
        if (classId != null) return classId.removePrefix("class:")
        val methodId = db.query(
            "SELECT id FROM nodes WHERE owning_file = ? AND corpus = 'code' AND id LIKE '%#%' LIMIT 1",
            owningFile,
        ) { it.getString("id") }.firstOrNull()
        return methodId?.substringBefore('#')
    }

    private fun supertypeFiles(fqcn: String): List<String> {
        val targets = db.query(
            "SELECT target_id FROM edges WHERE source_id = ? AND edge_type IN ('EXTENDS', 'IMPLEMENTS')",
            "class:$fqcn",
        ) { it.getString("target_id") }
        return targets.mapNotNull { resolveClassSourcePath(it.removePrefix("class:")) }
    }

    private fun paramTypes(content: String): String {
        val open = content.indexOf('(')
        if (open < 0) return ""
        var depth = 0
        var close = -1
        for (i in open until content.length) {
            when (content[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) { close = i; break }
                }
            }
        }
        if (close < 0) return ""
        val inner = content.substring(open + 1, close).trim()
        if (inner.isEmpty()) return ""
        return splitTopLevelCommas(inner).joinToString(",") { parameterType(it) }
    }

    private fun splitTopLevelCommas(params: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in params) {
            when (ch) {
                '<', '(', '[' -> { depth++; current.append(ch) }
                '>', ')', ']' -> { depth--; current.append(ch) }
                ',' -> if (depth == 0) { out.add(current.toString()); current.clear() } else current.append(ch)
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) out.add(current.toString())
        return out
    }

    private fun parameterType(param: String): String {
        val tokens = param.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && !it.startsWith("@") }
        if (tokens.size <= 1) return tokens.joinToString(" ")
        return tokens.dropLast(1).joinToString(" ")
    }

    fun close() {
        indexManager.closeAll()
    }
}

data class FilePathResult(
    val displayName: String,
    val filePath: String,
    val nodeType: String,
)

data class ClassMethod(
    val name: String,
    val lineStart: Int,
    val lineEnd: Int,
    val content: String,
    val declaringType: String = "",
    val inherited: Boolean = false,
)

data class MethodSourceMatch(
    val id: String,
    val owningFile: String,
    val lineStart: Int,
    val lineEnd: Int,
)

data class MethodSourceResolution(
    val matches: List<MethodSourceMatch>,
)

data class NodeIdResolution(
    val id: String?,
    val candidates: List<String>,
)
