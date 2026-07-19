// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.cli

import com.hyve.knowledge.core.config.KnowledgeConfig
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.eval.EvalRunner
import com.hyve.knowledge.core.eval.GoldenQuery
import com.hyve.knowledge.core.eval.GoldenSet
import com.hyve.knowledge.core.eval.SweepGrid
import com.hyve.knowledge.core.eval.ToolEval
import com.hyve.knowledge.core.index.CorpusIndexManager
import com.hyve.knowledge.core.logging.LogProvider
import com.hyve.knowledge.core.logging.StdoutLogProvider
import com.hyve.knowledge.core.search.KnowledgeSearchService
import com.hyve.knowledge.core.version.VersionResolver
import java.io.File
import kotlin.system.exitProcess

data class EvalArgs(
    val golden: String?,
    val patchline: String,
    val baseline: Double?,
    val sweep: List<String>,
) {
    companion object {
        val USAGE = """
            Hyve Knowledge Eval

            Usage: java -jar hyve-knowledge-indexer.jar eval [options]

            Options:
              --golden <path>       JSONL golden set path (default: checked-in seed)
              --patchline <name>    release | pre-release            (default: release)
              --baseline <double>   fail (exit 1) if overall Recall@5 < threshold
              --sweep <spec>        knob grid, e.g. hybridNameWeight=5,10;hybridRrfK=40,60
                                    (repeatable; axes form a cartesian product of combos)
        """.trimIndent()

        fun parse(args: List<String>): EvalArgs {
            val m = HashMap<String, String>()
            val sweep = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                when (val k = args[i]) {
                    "--golden", "--patchline", "--baseline" ->
                        m[k] = args.getOrElse(++i) { error("missing value for $k") }
                    "--sweep" ->
                        sweep.add(args.getOrElse(++i) { error("missing value for $k") })
                    else -> error("unknown eval arg: $k")
                }; i++
            }
            return EvalArgs(
                golden = m["--golden"],
                patchline = m["--patchline"] ?: "release",
                baseline = m["--baseline"]?.toDouble(),
                sweep = sweep,
            )
        }
    }
}

fun runEval(args: List<String>) {
    val opts = EvalArgs.parse(args)
    val log = StdoutLogProvider
    val queries: List<GoldenQuery> = opts.golden?.let { GoldenSet.load(it) } ?: GoldenSet.loadSeed()

    val baseConfig = KnowledgeConfig.loadFromFile() ?: KnowledgeConfig()
    val basePath = baseConfig.resolvedBasePath()
    val slug = VersionResolver.latestSlug(basePath, opts.patchline) ?: opts.patchline
    val config = baseConfig.copy(activeVersion = slug)

    val dbFile = File(config.resolvedIndexPath(), "knowledge.db")
    if (!dbFile.exists()) {
        log.error("No knowledge.db for patchline '${opts.patchline}' (slug '$slug') at ${dbFile.absolutePath}")
        exitProcess(1)
    }

    val db = KnowledgeDatabase.forFile(dbFile, log)
    val indexManager = CorpusIndexManager(config, log)

    try {
        if (opts.sweep.isNotEmpty()) {
            runSweep(db, indexManager, log, config, queries, opts.sweep)
            return
        }
        val service = KnowledgeSearchService(db, indexManager, log, config)
        try {
            val report = EvalRunner.evaluate(service, queries)
            println(EvalRunner.render(report))
            if (opts.baseline != null && report.overall.recallAt5 < opts.baseline) {
                log.error("Recall@5 ${report.overall.recallAt5} below baseline ${opts.baseline}")
                exitProcess(1)
            }
        } finally {
            service.close()
        }
    } finally {
        db.close()
    }
}

private fun runSweep(
    db: KnowledgeDatabase,
    indexManager: CorpusIndexManager,
    log: LogProvider,
    baseConfig: KnowledgeConfig,
    queries: List<GoldenQuery>,
    spec: List<String>,
) {
    val combos = SweepGrid.parse(spec.joinToString(";"))
    val knobs = combos.flatMap { it.keys }.distinct()
    val rows = combos.map { combo ->
        val service = KnowledgeSearchService(db, indexManager, log, SweepGrid.applyOverrides(baseConfig, combo))
        try {
            combo to EvalRunner.evaluate(service, queries).overall
        } finally {
            service.close()
        }
    }
    val best = rows.maxByOrNull { (_, m) -> m.recallAt5 * 1000.0 + m.mrr }
    println(renderSweep(rows, knobs, best?.first))
}

private fun renderSweep(
    rows: List<Pair<Map<String, String>, ToolEval>>,
    knobs: List<String>,
    bestCombo: Map<String, String>?,
): String {
    val sb = StringBuilder()
    sb.appendLine("=== Knob Sweep (${rows.size} combos, n=${rows.firstOrNull()?.second?.count ?: 0} queries) ===")
    sb.appendLine()
    val header = knobs.joinToString("  ") + (if (knobs.isEmpty()) "" else "  ") +
        "R@1     R@5     R@10    MRR     nDCG@10  best"
    sb.appendLine(header)
    for ((combo, m) in rows) {
        val knobCols = knobs.joinToString("  ") { combo[it] ?: "-" }
        val mark = if (combo == bestCombo) "*" else ""
        sb.appendLine(
            (if (knobs.isEmpty()) "" else "$knobCols  ") +
                "${num(m.recallAt1)}   ${num(m.recallAt5)}   ${num(m.recallAt10)}   ${num(m.mrr)}   ${num(m.ndcgAt10)}    $mark",
        )
    }
    sb.appendLine()
    if (bestCombo != null) {
        val bestStr = if (bestCombo.isEmpty()) "(base config)" else bestCombo.entries.joinToString(", ") { "${it.key}=${it.value}" }
        sb.appendLine("Best (by Recall@5, tie-break MRR): $bestStr")
    }
    return sb.toString()
}

private fun num(v: Double): String = String.format("%.3f", v)
