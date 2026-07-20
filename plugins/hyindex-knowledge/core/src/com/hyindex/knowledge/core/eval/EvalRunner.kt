// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.eval

import com.hyindex.knowledge.core.db.Corpus
import com.hyindex.knowledge.core.search.KnowledgeSearchService
import com.hyindex.knowledge.core.search.SearchResult

data class QueryEval(
    val query: GoldenQuery,
    val rankedIds: List<String>,
    val recallAt1: Double,
    val recallAt5: Double,
    val recallAt10: Double,
    val mrr: Double,
    val ndcgAt10: Double,
)

data class ToolEval(
    val tool: String,
    val count: Int,
    val recallAt1: Double,
    val recallAt5: Double,
    val recallAt10: Double,
    val mrr: Double,
    val ndcgAt10: Double,
)

data class EvalReport(
    val perQuery: List<QueryEval>,
    val perTool: List<ToolEval>,
    val overall: ToolEval,
)

object EvalRunner {
    private const val FETCH_LIMIT = 10

    fun dispatch(service: KnowledgeSearchService, query: GoldenQuery): List<SearchResult> =
        when (query.tool) {
            "search_hytale_code" ->
                service.searchCode(query.query, classFilter = null, limit = FETCH_LIMIT, pathPrefix = null)
            "search_hytale_gamedata" ->
                service.searchCorpus(query.query, Corpus.GAMEDATA, FETCH_LIMIT, service.detectGamedataIntent(query.query))
            "search_hytale_docs" ->
                service.searchCorpus(query.query, Corpus.DOCS, FETCH_LIMIT, null as String?)
            "search_hytale_client_code" ->
                service.searchCorpus(query.query, Corpus.CLIENT, FETCH_LIMIT, null as String?)
            else -> error("unknown tool in golden query: ${query.tool}")
        }

    fun evaluate(service: KnowledgeSearchService, queries: List<GoldenQuery>): EvalReport {
        val perQuery = queries.map { q ->
            val rankedIds = dispatch(service, q).map { it.nodeId }
            QueryEval(
                query = q,
                rankedIds = rankedIds,
                recallAt1 = recallAtK(rankedIds, q.expectedIds, 1),
                recallAt5 = recallAtK(rankedIds, q.expectedIds, 5),
                recallAt10 = recallAtK(rankedIds, q.expectedIds, 10),
                mrr = reciprocalRank(rankedIds, q.expectedIds),
                ndcgAt10 = ndcg(rankedIds, q.expectedIds, 10),
            )
        }
        val perTool = perQuery.groupBy { it.query.tool }
            .map { (tool, group) -> aggregate(tool, group) }
            .sortedBy { it.tool }
        return EvalReport(perQuery, perTool, aggregate("overall", perQuery))
    }

    private fun aggregate(tool: String, group: List<QueryEval>): ToolEval = ToolEval(
        tool = tool,
        count = group.size,
        recallAt1 = group.map { it.recallAt1 }.average(),
        recallAt5 = group.map { it.recallAt5 }.average(),
        recallAt10 = group.map { it.recallAt10 }.average(),
        mrr = group.map { it.mrr }.average(),
        ndcgAt10 = group.map { it.ndcgAt10 }.average(),
    )

    fun render(report: EvalReport): String {
        val sb = StringBuilder()
        sb.appendLine("=== Retrieval Eval ===")
        sb.appendLine()
        for (q in report.perQuery) {
            val status = if (q.recallAt10 > 0.0) "HIT" else "MISS"
            sb.appendLine("[$status] ${q.query.tool}  R@1=${pct(q.recallAt1)} R@5=${pct(q.recallAt5)} R@10=${pct(q.recallAt10)} MRR=${num(q.mrr)} nDCG@10=${num(q.ndcgAt10)}")
            sb.appendLine("       query: ${q.query.query}")
            sb.appendLine("       expected: ${q.query.expectedIds.joinToString(", ")}")
            sb.appendLine("       top: ${q.rankedIds.take(5).joinToString(", ").ifEmpty { "(none)" }}")
        }
        sb.appendLine()
        sb.appendLine("--- Per tool ---")
        for (t in report.perTool) sb.appendLine(line(t))
        sb.appendLine()
        sb.appendLine("--- Overall ---")
        sb.appendLine(line(report.overall))
        return sb.toString()
    }

    private fun line(t: ToolEval): String =
        "${t.tool} (n=${t.count})  R@1=${num(t.recallAt1)} R@5=${num(t.recallAt5)} R@10=${num(t.recallAt10)} MRR=${num(t.mrr)} nDCG@10=${num(t.ndcgAt10)}"

    private fun pct(v: Double): String = if (v >= 1.0) "1" else "0"

    private fun num(v: Double): String = String.format("%.3f", v)
}
