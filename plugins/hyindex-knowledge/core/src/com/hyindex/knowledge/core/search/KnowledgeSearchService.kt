// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.search

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.embedding.EmbeddingCompatibility
import com.hyindex.knowledge.core.embedding.EmbeddingException
import com.hyindex.knowledge.core.embedding.Reranker
import com.hyindex.knowledge.core.embedding.RerankerFactory
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
    corpusRouter: CorpusRouter? = null,
) {

    private val rerankerProfile = config.rerankerProfile
    private val reranker: Reranker? = reranker ?: rerankerProfile?.let {
        RerankerFactory.fromProfile(it, log)
    }
    private val rerankerTopN = rerankerProfile?.topN ?: 50

    private val corpusRouter: CorpusRouter? = corpusRouter ?: if (config.jevRoutingEnabled) {
        val apiKey = config.jevApiKey.ifBlank { System.getenv("TYPESAFE_API_KEY").orEmpty() }
        if (apiKey.isBlank()) {
            log.warn("Jev corpus routing is enabled but no API key is configured")
            null
        } else {
            JevCorpusRouter(
                apiKey = apiKey,
                model = config.jevModel,
                baseUrl = config.jevBaseUrl,
                threshold = config.jevCorpusThreshold,
                log = log,
            )
        }
    } else null

    internal fun authorizeCorpora(requested: List<Corpus>): List<Corpus> = requested.distinct()

    internal fun isAuthorized(corpus: Corpus, relativePath: String): Boolean = true

    internal fun isAuthorized(result: SearchResult): Boolean =
        Corpus.entries.any { it.id.equals(result.corpus, ignoreCase = true) }

    internal fun isAuthorized(corpusId: String?, relativePath: String): Boolean =
        Corpus.entries.any { it.id.equals(corpusId, ignoreCase = true) }

    internal fun filterAuthorized(results: List<SearchResult>): List<SearchResult> =
        results.filter(::isAuthorized)

    internal fun maybeRerank(query: String, results: List<SearchResult>, intent: String? = null): List<SearchResult> {
        val authorized = filterAuthorized(results)
        val active = reranker ?: return authorized
        if (rerankerProfile == null || authorized.size < 2) return authorized

        val candidateCount = minOf(rerankerTopN, authorized.size)
        val candidates = authorized.take(candidateCount)
        val tail = authorized.drop(candidateCount)

        val rerankQuery = rerankInstruction(intent)?.let { "$query\n\nIntent: $intent. $it" } ?: query
        val substantiveContent = rerankContentFor(candidates)
        val order = active.rerank(
            rerankQuery,
            candidates.map { rerankDocument(it, substantiveContent[it.nodeId]) },
        )
        if (order.isEmpty()) return authorized

        val reordered = order.mapIndexedNotNull { position, (index, relevance) ->
            candidates.getOrNull(index)?.copy(
                score = 1.0 - position / candidateCount.toDouble(),
                relevanceScore = relevance,
            )
        }
        return reordered + tail
    }

    internal fun requireCompatibleQueryEmbeddings(corpus: Corpus) {
        indexManager.requireCompatibleQueryDimensions(corpus)
        val stored = loadQueryProvenance(corpus) ?: return
        val profile = config.resolvedEmbeddingProfile(corpus)
        val queryFamily = EmbeddingCompatibility.family(profile.queryModel)
        if (!profile.provider.equals(stored.provider, ignoreCase = true) || queryFamily != stored.family) {
            throw EmbeddingException.IncompatibleFamily(
                "${stored.provider}:${stored.family}",
                "${profile.provider}:${profile.queryModel}",
            )
        }
        val queryDimension = indexManager.getQueryProvider(corpus).dimension
        if (queryDimension != stored.dimensions) {
            throw EmbeddingException.DimensionMismatch(stored.dimensions, queryDimension)
        }
    }

    private data class StoredQueryProvenance(
        val provider: String,
        val family: String,
        val dimensions: Int,
    )

    private fun loadQueryProvenance(corpus: Corpus): StoredQueryProvenance? {
        return try {
            db.query(
                "SELECT provider, query_compatible_family, dimensions FROM corpus_provenance WHERE corpus = ?",
                corpus.id,
            ) { rs ->
                StoredQueryProvenance(
                    provider = rs.getString("provider").orEmpty(),
                    family = rs.getString("query_compatible_family").orEmpty(),
                    dimensions = rs.getInt("dimensions"),
                )
            }.firstOrNull { it.provider.isNotEmpty() && it.family.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }





    fun reinitialize(newDb: KnowledgeDatabase, newIndexManager: CorpusIndexManager) {
        log.info("Reinitializing search service with new database and index manager")
        indexManager.closeAll()

        db = newDb
        indexManager = newIndexManager
    }


    fun search(query: String, limit: Int = 10): List<SearchResult> {
        if (query.isBlank()) return emptyList()
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
        val filtered = filterAuthorized(filterByFacets(pathFiltered, visibility, annotation))
        val ranked = if (rerank && rerankerProfile != null) maybeRerank(query, filtered) else filtered
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
        return filterAuthorized(db.query(
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
        })
    }

    internal fun metadataIsThin(metadata: String?): Boolean {
        if (metadata.isNullOrBlank()) return false
        return Regex("\"thin\"\\s*:\\s*true").containsMatchIn(metadata)
    }

    fun vectorSearch(query: String, db: KnowledgeDatabase, limit: Int): List<SearchResult> {
        val hnsw = indexManager.getIndex(Corpus.CODE) ?: return emptyList()
        val provider = indexManager.getQueryProvider(Corpus.CODE)
        requireCompatibleQueryEmbeddings(Corpus.CODE)

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
        if (query.isBlank()) return emptyList()
        if (corpus == Corpus.CODE) return searchCode(query, dataTypeFilters?.firstOrNull(), limit, rerank)

        val hnsw = indexManager.getIndex(corpus) ?: return emptyList()
        val provider = indexManager.getQueryProvider(corpus)
        requireCompatibleQueryEmbeddings(corpus)

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
        val filtered = filterAuthorized(filterBySnippet(typeFiltered, snippetContains))
        val ranked = if (rerank && rerankerProfile != null) maybeRerank(query, filtered) else filtered
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
            }.let { filterAuthorized(it) }
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
        val norm = scores.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        return best.values
            .map { it.copy(score = (scores[it.nodeId] ?: 0.0) / norm) }
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


    fun searchAcrossCorpora(
        query: String,
        corpora: List<Corpus>,
        perCorpus: Int = 10,
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val requested = authorizeCorpora(corpora)
        if (requested.isEmpty()) return emptyList()
        val route = try {
            corpusRouter?.route(query, requested)
        } catch (e: Exception) {
            log.warn("Corpus routing failed: ${e.message}")
            null
        }
        val selectedCorpora = authorizeCorpora(route?.corpora.orEmpty()).ifEmpty { requested }
        val budgets = candidateBudgets(selectedCorpora, route, perCorpus)
        if (route != null) {
            log.info("Jev route: intent=${route.intent}, corpora=${selectedCorpora.joinToString { it.id }}")
        }
        val candidates = mutableListOf<SearchResult>()
        var remainingCapacity = if (route == null) Int.MAX_VALUE else rerankerTopN
        for ((corpus, budget) in budgets) {
            val requestLimit = minOf(budget, remainingCapacity)
            if (requestLimit <= 0) continue
            val found = try {
                searchCorpus(query, corpus, requestLimit, rerank = false)
            } catch (e: Exception) {
                log.warn("Search failed for corpus ${corpus.id}: ${e.message}")
                emptyList()
            }
            candidates += found
            if (remainingCapacity != Int.MAX_VALUE) remainingCapacity -= found.size
        }
        if (route != null && remainingCapacity > 0) {
            for ((corpus, budget) in budgets) {
                if (budget > 0 || remainingCapacity <= 0) continue
                val found = try {
                    searchCorpus(query, corpus, minOf(15, remainingCapacity), rerank = false)
                } catch (e: Exception) {
                    log.warn("Search failed for fallback corpus ${corpus.id}: ${e.message}")
                    emptyList()
                }
                candidates += found
                remainingCapacity -= found.size
            }
        }
        val ranked = maybeRerank(query, rankCrossCorpus(candidates), route?.intent)
        if (!needsBroaden(ranked, route)) {
            return diversifyBySource(ranked).take(perCorpus)
        }

        val primary = budgets.maxByOrNull { it.value }?.key
        val existingIds = candidates.mapTo(mutableSetOf()) { it.nodeId }
        val widened = selectedCorpora.flatMap { corpus ->
            val widerBudget = if (corpus == primary) 45 else 15
            try {
                searchCorpus(query, corpus, widerBudget, rerank = false)
                    .filter { existingIds.add(it.nodeId) }
            } catch (e: Exception) {
                log.warn("Adaptive search failed for corpus ${corpus.id}: ${e.message}")
                emptyList()
            }
        }
        val omitted = requested.filter { it !in selectedCorpora }.flatMap { corpus ->
            try {
                searchCorpus(query, corpus, 10, rerank = false)
                    .filter { existingIds.add(it.nodeId) }
            } catch (e: Exception) {
                log.warn("Adaptive search failed for omitted corpus ${corpus.id}: ${e.message}")
                emptyList()
            }
        }
        val expanded = expandCrossCorpus(ranked, selectedCorpora, perCorpus)
            .filter { it.score >= config.minExpansionResultScore }
        val broadened = maybeRerank(
            query,
            rankCrossCorpus(candidates + widened + omitted + expanded),
            route?.intent,
        )
        return diversifyBySource(broadened).take(perCorpus)
    }


    fun searchWithExpansion(
        query: String,
        corpora: List<Corpus>,
        perCorpus: Int = 10,
        expansionLimit: Int = 5,
    ): List<SearchResult> {
        val authorizedCorpora = authorizeCorpora(corpora)
        val gamedataTypeHint = detectGamedataIntent(query)
        val directResults = authorizedCorpora.flatMap { corpus ->
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

        val firstPass = maybeRerank(query, rankCrossCorpus(directResults))
        if (!needsBroaden(firstPass)) {
            return diversifyBySource(firstPass)
        }

        val expanded = expandCrossCorpus(firstPass, authorizedCorpora, expansionLimit)
            .filter { it.score >= config.minExpansionResultScore }
        log.info("Graph expansion: ${directResults.size} direct → ${expanded.size} expanded results")

        val seedToExpanded = expanded.groupBy { it.expandedFromNodeId ?: "" }.filterKeys { it.isNotEmpty() }
            .mapValues { (_, results) -> results.map { it.nodeId } }

        val annotatedDirect = firstPass.map { result ->
            val connections = seedToExpanded[result.nodeId]
            if (connections != null) result.copy(connectedNodeIds = connections) else result
        }

        return diversifyBySource(maybeRerank(query, rankCrossCorpus(annotatedDirect + expanded)))
    }


    private companion object {
        private val RERANK_BOILERPLATE = Regex(
            "(?im)^(?:(?:Official Hytale (?:Docs|Blog|Support|Shared Source)|HytaleModding Community Docs|Hytale Modding Docs):|Type:|// Package:|// Class:|Purpose:|Corpus:).*\\R?",
        )
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
        return filterAuthorized(expanded)
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

    internal fun diversifyBySource(results: List<SearchResult>): List<SearchResult> {
        val authorized = filterAuthorized(results)
        val seen = mutableMapOf<String, Int>()
        val out = ArrayList<SearchResult>(authorized.size)
        for (result in authorized) {
            val cap = if (result.corpus == "code") 2 else 1
            val source = result.filePath.ifBlank {
                result.nodeId.substringBefore('#').substringBeforeLast(':', result.nodeId)
            }
            val key = "${result.corpus}\u0000$source"
            val used = seen[key] ?: 0
            if (used >= cap) continue
            seen[key] = used + 1
            out.add(result)
        }
        return out
    }

    internal fun candidateBudgets(
        selected: List<Corpus>,
        route: CorpusRoute?,
        perCorpus: Int,
    ): Map<Corpus, Int> {
        if (route == null) return selected.associateWith { perCorpus }
        val topN = rerankerTopN.coerceAtLeast(0)
        val primaryTarget = (config.routedCandidatesPerCorpus * 2).coerceIn(25, 30)
        val secondaryMin = 10
        val secondaryMax = 15
        val probabilities = route.probabilities
        val ordered = selected.sortedByDescending { probabilities[it] ?: 0.0 }
        val primary = ordered.firstOrNull() ?: return emptyMap()
        val secondaries = ordered.drop(1)
        val strongestSecondary = secondaries.maxOfOrNull { probabilities[it] ?: 0.0 } ?: 0.0
        val reserveForSecondaries = when {
            secondaries.size >= 2 && topN >= 45 -> 25
            secondaries.isNotEmpty() -> minOf(15, (topN - primaryTarget).coerceAtLeast(0))
            else -> 0
        }
        val primaryBudget = if (topN in 25..44) {
            minOf(primaryTarget, topN)
        } else {
            minOf(primaryTarget, (topN - reserveForSecondaries).coerceAtLeast(0))
        }
        var remaining = topN - primaryBudget
        val budgets = linkedMapOf(primary to primaryBudget)
        for (corpus in secondaries) {
            if (remaining < secondaryMin) {
                budgets[corpus] = 0
                continue
            }
            val relativeWeight = if (strongestSecondary > 0.0) {
                ((probabilities[corpus] ?: 0.0) / strongestSecondary).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val desired = (secondaryMin + kotlin.math.round(
                (secondaryMax - secondaryMin) * relativeWeight,
            ).toInt()).coerceIn(secondaryMin, secondaryMax)
            val allocated = minOf(desired, remaining)
            budgets[corpus] = allocated
            remaining -= allocated
        }
        return budgets
    }

    private fun needsBroaden(results: List<SearchResult>, route: CorpusRoute? = null): Boolean {
        val scores = results.mapNotNull { it.relevanceScore }.sortedDescending()
        if (scores.isEmpty()) return (route?.graphExpansionProbability ?: 0.0) >= 0.65
        val best = scores.first()
        if (best >= 0.65) return false
        if (best < 0.5) return true
        if ((route?.graphExpansionProbability ?: 0.0) >= 0.65) return true
        val relevanceMargin = best - (scores.getOrNull(1) ?: return false)
        val routeScores = route?.probabilities?.values?.sortedDescending().orEmpty()
        val routeMargin = if (routeScores.size >= 2) routeScores[0] - routeScores[1] else 1.0
        return relevanceMargin < 0.08 && routeMargin < 0.15
    }

    private fun rerankInstruction(intent: String?): String? = when (intent) {
        "api_how_to" ->
            "Rank actionable guides, examples, and public APIs above incidental mentions or internal-only details."
        "exact_symbol" ->
            "Rank the exact requested symbol or file first; prefer canonical declarations over references."
        "implementation" ->
            "Rank the code that performs or validates the behavior above guides, callers, and incidental mentions."
        "game_data" ->
            "Rank exact game-data definitions and their directly relevant schema or implementation."
        "client_ui" ->
            "Rank the exact client UI layout or implementation above unrelated server and game-data matches."
        else -> null
    }

    private fun rerankContentFor(results: List<SearchResult>): Map<String, String> {
        if (results.isEmpty()) return emptyMap()
        val placeholders = results.joinToString(",") { "?" }
        return db.query(
            "SELECT id, content FROM nodes WHERE id IN ($placeholders)",
            *results.map { it.nodeId }.toTypedArray(),
        ) { rs -> rs.getString("id") to (rs.getString("content") ?: "") }
            .toMap()
    }

    private fun rerankDocument(result: SearchResult, content: String?): String = buildString {
        append("corpus: ").appendLine(result.corpus)
        append("title: ").appendLine(result.displayName)
        append("path: ").appendLine(result.filePath)
        result.dataType?.takeIf { it.isNotBlank() }?.let { append("type: ").appendLine(it) }
        appendLine()
        val substantive = content?.takeIf { it.isNotBlank() } ?: result.snippet
        append(stripRerankBoilerplate(substantive).take(config.snippetMaxLength))
    }

    private fun stripRerankBoilerplate(text: String): String =
        text.replace(RERANK_BOILERPLATE, "").trim()


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
        return results.firstOrNull()?.takeIf { isAuthorized(it) }
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
        }.filter { isAuthorized(Corpus.CODE, it.filePath) }

    }

    fun resolveClassSourcePath(className: String): String? {
        val name = className.trim()
        if (name.isEmpty()) return null
        val path = if ('.' in name) {
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
        return path?.takeIf { isAuthorized(Corpus.CODE, it) }
    }

    fun resolveMethodSource(className: String?, methodName: String): MethodSourceResolution {
        val method = methodName.trim()
        if (method.isEmpty()) return MethodSourceResolution(emptyList())
        val rows = if ('#' in method) {
            db.query(
                """SELECT id, owning_file, line_start, line_end FROM nodes
                   WHERE corpus = 'code' AND owning_file IS NOT NULL
                     AND (display_name = ? OR id = ? OR id LIKE ?)""",
                method, method, "%.$method",
            ) { rs -> MethodSourceMatch(rs.getString("id"), rs.getString("owning_file"), rs.getInt("line_start"), rs.getInt("line_end")) }
        } else {
            val cls = className?.trim()?.takeIf { it.isNotEmpty() }
            if (cls != null) {
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
        }
        val distinct = rows.filter { isAuthorized(Corpus.CODE, it.owningFile) }.distinctBy { it.id }
        return MethodSourceResolution(distinct)
    }

    fun resolveNodeId(raw: String): NodeIdResolution {
        val id = raw.trim()
        if (id.isEmpty()) return NodeIdResolution(null, emptyList())
        val exact = db.query(
            "SELECT id, corpus, file_path FROM nodes WHERE id = ? LIMIT 1", id,
        ) { Triple(it.getString("id"), it.getString("corpus"), it.getString("file_path") ?: "") }
        val exactRow = exact.firstOrNull()
        if (exactRow != null) {
            return if (isAuthorized(exactRow.second, exactRow.third)) NodeIdResolution(id, emptyList())
            else NodeIdResolution(null, emptyList())
        }
        val refs = db.query(
            "SELECT DISTINCT id, corpus, file_path FROM nodes WHERE display_name = ? OR id LIKE ?",
            id, "%.$id",
        ) { Triple(it.getString("id"), it.getString("corpus"), it.getString("file_path") ?: "") }
            .filter { isAuthorized(it.second, it.third) }
            .map { it.first }
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
            if (!isAuthorized(Corpus.CODE, owningFile)) continue
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
