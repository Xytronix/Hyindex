// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.eval

import kotlin.math.ln

private fun isHit(id: String, expectedIds: List<String>): Boolean =
    expectedIds.any { id.contains(it, ignoreCase = true) }

private fun firstHitRank(rankedIds: List<String>, expectedIds: List<String>): Int {
    rankedIds.forEachIndexed { index, id ->
        if (isHit(id, expectedIds)) return index + 1
    }
    return 0
}

fun recallAtK(rankedIds: List<String>, expectedIds: List<String>, k: Int): Double {
    val rank = firstHitRank(rankedIds.take(k), expectedIds)
    return if (rank > 0) 1.0 else 0.0
}

fun reciprocalRank(rankedIds: List<String>, expectedIds: List<String>): Double {
    val rank = firstHitRank(rankedIds, expectedIds)
    return if (rank > 0) 1.0 / rank else 0.0
}

fun ndcg(rankedIds: List<String>, expectedIds: List<String>, k: Int): Double {
    val rank = firstHitRank(rankedIds.take(k), expectedIds)
    if (rank == 0) return 0.0
    val dcg = 1.0 / log2(rank + 1)
    val idcg = 1.0 / log2(2)
    return dcg / idcg
}

private fun log2(x: Int): Double = ln(x.toDouble()) / ln(2.0)
