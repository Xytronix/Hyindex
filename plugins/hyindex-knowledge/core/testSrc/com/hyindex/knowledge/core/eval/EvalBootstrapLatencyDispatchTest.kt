// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.eval

import com.hyindex.knowledge.core.config.KnowledgeConfig
import com.hyindex.knowledge.core.db.KnowledgeDatabase
import com.hyindex.knowledge.core.index.CorpusIndexManager
import com.hyindex.knowledge.core.search.KnowledgeSearchService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class EvalBootstrapLatencyDispatchTest {

    @Test
    fun `bootstrap mean CI is deterministic for a fixed seed`() {
        val values = listOf(0.0, 0.5, 1.0, 0.8, 0.2, 0.9, 0.1, 0.4)
        val a = bootstrapMeanCi(values, resamples = 1000, seed = 42L, confidence = 0.95)
        val b = bootstrapMeanCi(values, resamples = 1000, seed = 42L, confidence = 0.95)
        assertEquals(a.mean, b.mean, 0.0)
        assertEquals(a.lower, b.lower, 0.0)
        assertEquals(a.upper, b.upper, 0.0)
        assertEquals(values.average(), a.mean, 1e-9)
        assertTrue(a.lower <= a.mean && a.mean <= a.upper)
        assertTrue(a.lower < a.upper, "resampled interval must have width on mixed values")
    }

    @Test
    fun `evaluate records per-query latency and aggregated percentiles`() {
        val service = emptyService()
        val report = EvalRunner.evaluate(
            service,
            listOf(
                GoldenQuery("search_hytale_code", "get player inventory", listOf("getInventory")),
                GoldenQuery("search_hytale_docs", "how to create a custom block", listOf("creating-block")),
            ),
        )
        assertEquals(2, report.perQuery.size)
        assertTrue(report.perQuery.all { it.latencyMs >= 0.0 })
        assertTrue(report.overall.latencyP50Ms >= 0.0)
        assertTrue(report.overall.latencyP95Ms >= report.overall.latencyP50Ms)
        service.let { }
    }

    @Test
    fun `dispatch routes search_hytale as unified cross-corpus search`() {
        val dbFile = Files.createTempFile("eval_dispatch_", ".db").toFile()
        dbFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(dbFile)
        val service = KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
        val query = GoldenQuery(
            tool = "search_hytale",
            query = "how to create a custom block",
            expectedIds = listOf("creating-block"),
            intent = "howto",
            expectedCorpora = listOf("docs", "code"),
        )
        val result = runCatching { EvalRunner.dispatch(service, query) }
        assertTrue(
            result.isSuccess,
            "search_hytale must dispatch through unified search, not fail as an unknown tool: ${result.exceptionOrNull()?.message}",
        )
        db.close()
    }

    private fun emptyService(): KnowledgeSearchService {
        val dbFile = Files.createTempFile("eval_latency_", ".db").toFile()
        dbFile.deleteOnExit()
        val db = KnowledgeDatabase.forFile(dbFile)
        return KnowledgeSearchService(db, CorpusIndexManager(KnowledgeConfig()))
    }
}
