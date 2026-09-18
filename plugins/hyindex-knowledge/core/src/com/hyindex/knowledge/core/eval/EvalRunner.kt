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
    val latencyMs: Double = 0.0,
)

data class ToolEval(
    val tool: String,
    val count: Int,
    val recallAt1: Double,
    val recallAt5: Double,
    val recallAt10: Double,
    val mrr: Double,
    val ndcgAt10: Double,
    val recallAt5Ci: ConfidenceInterval,
    val mrrCi: ConfidenceInterval,
    val ndcgAt10Ci: ConfidenceInterval,
    val latencyP50Ms: Double = 0.0,
    val latencyP95Ms: Double = 0.0,
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
            "search_hytale" ->
                service.searchAcrossCorpora(query.query, Corpus.SEARCH_DEFAULT, FETCH_LIMIT)
            else -> error("unknown tool in golden query: ${query.tool}")
        }

    fun evaluate(service: KnowledgeSearchService, queries: List<GoldenQuery>): EvalReport {
        val perQuery = queries.map { query ->
            val started = System.nanoTime()
            val rankedIds = dispatch(service, query).map { it.nodeId }
            val latencyMs = (System.nanoTime() - started) / 1_000_000.0
            val relevant = query.expectedIds
            val ndcgValue = query.judgments?.takeIf { it.isNotEmpty() }
                ?.let { gradedNdcg(rankedIds, it, 10) }
                ?: ndcg(rankedIds, relevant, 10)
            QueryEval(
                query = query,
                rankedIds = rankedIds,
                recallAt1 = multiLabelRecallAtK(rankedIds, relevant, 1),
                recallAt5 = multiLabelRecallAtK(rankedIds, relevant, 5),
                recallAt10 = multiLabelRecallAtK(rankedIds, relevant, 10),
                mrr = reciprocalRank(rankedIds, relevant),
                ndcgAt10 = ndcgValue,
                latencyMs = latencyMs,
            )
        }
        val perTool = perQuery.groupBy { it.query.tool }
            .map { (tool, group) -> aggregate(tool, group) }
            .sortedBy { it.tool }
        return EvalReport(perQuery, perTool, aggregate("overall", perQuery))
    }

    private fun aggregate(tool: String, group: List<QueryEval>): ToolEval {
        val latencies = group.map { it.latencyMs }.sorted()
        return ToolEval(
            tool = tool,
            count = group.size,
            recallAt1 = group.map { it.recallAt1 }.average(),
            recallAt5 = group.map { it.recallAt5 }.average(),
            recallAt10 = group.map { it.recallAt10 }.average(),
            mrr = group.map { it.mrr }.average(),
            ndcgAt10 = group.map { it.ndcgAt10 }.average(),
            recallAt5Ci = bootstrapMeanCi(group.map { it.recallAt5 }),
            mrrCi = bootstrapMeanCi(group.map { it.mrr }),
            ndcgAt10Ci = bootstrapMeanCi(group.map { it.ndcgAt10 }),
            latencyP50Ms = percentile(latencies, 0.50),
            latencyP95Ms = percentile(latencies, 0.95),
        )
    }

    fun render(report: EvalReport): String = buildString {
        appendLine("=== Retrieval Eval ===")
        appendLine()
        for (query in report.perQuery) {
            val status = if (query.recallAt10 > 0.0) "HIT" else "MISS"
            appendLine(
                "[$status] ${query.query.tool}  R@1=${pct(query.recallAt1)} " +
                    "R@5=${pct(query.recallAt5)} R@10=${pct(query.recallAt10)} " +
                    "MRR=${num(query.mrr)} nDCG@10=${num(query.ndcgAt10)}",
            )
            appendLine("       query: ${query.query.query}")
            appendLine("       expected: ${query.query.expectedIds.joinToString(", ")}")
            appendLine("       top: ${query.rankedIds.take(5).joinToString(", ").ifEmpty { "(none)" }}")
            appendLine("       latencyMs: ${num(query.latencyMs)}")
        }
        appendLine()
        appendLine("--- Per tool ---")
        for (tool in report.perTool) appendLine(line(tool))
        appendLine()
        appendLine("--- Overall ---")
        appendLine(line(report.overall))
    }

    private fun line(tool: ToolEval): String =
        "${tool.tool} (n=${tool.count})  R@1=${num(tool.recallAt1)} " +
            "R@5=${num(tool.recallAt5)}${ci(tool.recallAt5Ci)} R@10=${num(tool.recallAt10)} " +
            "MRR=${num(tool.mrr)}${ci(tool.mrrCi)} nDCG@10=${num(tool.ndcgAt10)}${ci(tool.ndcgAt10Ci)} " +
            "p50=${num(tool.latencyP50Ms)}ms p95=${num(tool.latencyP95Ms)}ms"

    private fun pct(value: Double): String = if (value >= 1.0) "1" else "0"
    private fun num(value: Double): String = String.format("%.3f", value)
    private fun ci(value: ConfidenceInterval): String = "[${num(value.lower)},${num(value.upper)}]"
}
