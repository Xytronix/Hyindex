// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.eval

import kotlin.math.ln
import kotlin.random.Random

data class ConfidenceInterval(
    val mean: Double,
    val lower: Double,
    val upper: Double,
)

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

fun multiLabelRecallAtK(rankedIds: List<String>, expectedIds: List<String>, k: Int): Double {
    if (expectedIds.isEmpty()) return 0.0
    val top = rankedIds.take(k)
    val hits = expectedIds.count { label -> top.any { it.contains(label, ignoreCase = true) } }
    return hits.toDouble() / expectedIds.size
}



fun gradedNdcg(rankedIds: List<String>, judgments: List<GradedJudgment>, k: Int): Double {
    if (judgments.isEmpty()) return 0.0
    val used = BooleanArray(judgments.size)
    val gains = rankedIds.take(k).map { id ->
        val match = judgments.indices
            .filter { !used[it] && id.contains(judgments[it].id, ignoreCase = true) }
            .maxByOrNull { judgments[it].grade }
        if (match == null) {
            0.0
        } else {
            used[match] = true
            gain(judgments[match].grade)
        }
    }
    val dcg = gains.mapIndexed { index, value ->
        if (value == 0.0) 0.0 else value / log2(index + 2)
    }.sum()
    val ideal = judgments.map { gain(it.grade) }.sortedDescending().take(k)
        .mapIndexed { index, value -> value / log2(index + 2) }.sum()
    return if (ideal == 0.0) 0.0 else dcg / ideal
}

fun bootstrapMeanCi(
    values: List<Double>,
    resamples: Int = 1000,
    seed: Long = 42L,
    confidence: Double = 0.95,
): ConfidenceInterval {
    if (values.isEmpty()) return ConfidenceInterval(0.0, 0.0, 0.0)
    val mean = values.average()
    if (values.size == 1) return ConfidenceInterval(mean, mean, mean)
    val rng = Random(seed)
    val n = values.size
    val sampleMeans = DoubleArray(resamples) {
        var sum = 0.0
        repeat(n) { sum += values[rng.nextInt(n)] }
        sum / n
    }
    sampleMeans.sort()
    val alpha = (1.0 - confidence) / 2.0
    return ConfidenceInterval(
        mean = mean,
        lower = percentile(sampleMeans, alpha),
        upper = percentile(sampleMeans, 1.0 - alpha),
    )
}

fun percentile(values: DoubleArray, p: Double): Double {
    if (values.isEmpty()) return 0.0
    if (values.size == 1) return values[0]
    val idx = p.coerceIn(0.0, 1.0) * (values.size - 1)
    val lo = idx.toInt()
    val hi = (lo + 1).coerceAtMost(values.lastIndex)
    val frac = idx - lo
    return values[lo] * (1.0 - frac) + values[hi] * frac
}

fun percentile(values: List<Double>, p: Double): Double = percentile(values.toDoubleArray(), p)

private fun gain(rel: Int): Double = (1 shl rel.coerceAtLeast(0)) - 1.0

private fun log2(x: Int): Double = ln(x.toDouble()) / ln(2.0)
