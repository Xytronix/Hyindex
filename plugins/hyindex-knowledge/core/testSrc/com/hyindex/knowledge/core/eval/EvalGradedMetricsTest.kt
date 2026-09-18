// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln

class EvalGradedMetricsTest {

    private val judgments = listOf(
        GradedJudgment("creating-block", 3),
        GradedJudgment("AbstractBlock", 1),
    )

    @Test
    fun `multi-label recall is the fraction of relevant labels in top-k not a first-hit binary`() {
        val ranked = listOf("docs/creating-block.md", "noise", "server.AbstractBlock#place")
        assertEquals(0.5, multiLabelRecallAtK(ranked, listOf("creating-block", "AbstractBlock"), 1), 1e-9)
        assertEquals(0.5, multiLabelRecallAtK(ranked, listOf("creating-block", "AbstractBlock"), 2), 1e-9)
        assertEquals(1.0, multiLabelRecallAtK(ranked, listOf("creating-block", "AbstractBlock"), 3), 1e-9)
        assertEquals(0.0, multiLabelRecallAtK(listOf("noise"), listOf("creating-block", "AbstractBlock"), 5), 1e-9)
    }

    @Test
    fun `binary first-hit recallAtK must not be reused as multi-label recall`() {
        val ranked = listOf("creating-block", "unrelated")
        val labels = listOf("creating-block", "AbstractBlock")
        assertEquals(1.0, recallAtK(ranked, labels, 5), "legacy first-hit still reports a hit")
        assertEquals(0.5, multiLabelRecallAtK(ranked, labels, 5), 1e-9)
    }

    @Test
    fun `graded nDCG uses 2^grade-1 gains and is not binary first-hit nDCG`() {
        val ranked = listOf("AbstractBlock", "creating-block")
        val observed = gradedNdcg(ranked, judgments, 2)
        val dcg = gain(1) / log2(2) + gain(3) / log2(3)
        val idcg = gain(3) / log2(2) + gain(1) / log2(3)
        assertEquals(dcg / idcg, observed, 1e-9)

        val binary = ndcg(ranked, listOf("creating-block", "AbstractBlock"), 2)
        assertEquals(1.0, binary, "binary nDCG saturates on any first-rank hit")
        assertTrue(observed < 1.0, "a grade-1 hit above a grade-3 hit must not be a perfect ranking")
    }

    @Test
    fun `ideal graded ranking scores 1 and misses score 0`() {
        assertEquals(1.0, gradedNdcg(listOf("creating-block", "AbstractBlock"), judgments, 10), 1e-9)
        assertEquals(0.0, gradedNdcg(listOf("noise", "other"), judgments, 10), 1e-9)
    }

    @Test
    fun `graded nDCG matches substring ids like legacy metrics`() {
        val ranked = listOf("docs/guides/creating-block.html")
        assertEquals(
            1.0,
            gradedNdcg(ranked, listOf(GradedJudgment("creating-block", 2)), 5),
            1e-9,
        )
    }

    @Test
    fun `graded nDCG counts each judgment at most once`() {
        val duplicateChunks = listOf(
            "docs/creating-block.mdx#overview",
            "docs/creating-block.mdx#example",
        )
        val score = gradedNdcg(duplicateChunks, listOf(GradedJudgment("creating-block", 3)), 10)
        assertEquals(1.0, score, 1e-9)
    }

    private fun gain(rel: Int): Double = (1 shl rel) - 1.0
    private fun log2(x: Int): Double = ln(x.toDouble()) / ln(2.0)
}
