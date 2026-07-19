// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class EvalMetricsTest {

    private val ranked = listOf("a", "b", "hit", "c", "d")
    private val expected = listOf("hit")

    @Test fun `recallAtK is 1 when an expected hit is within top-k`() {
        assertEquals(1.0, recallAtK(ranked, expected, 5))
        assertEquals(1.0, recallAtK(ranked, expected, 3))
    }

    @Test fun `recallAtK is 0 when the hit is below top-k`() {
        assertEquals(0.0, recallAtK(ranked, expected, 1))
        assertEquals(0.0, recallAtK(ranked, expected, 2))
    }

    @Test fun `recallAtK is 0 when there is no hit`() {
        assertEquals(0.0, recallAtK(ranked, listOf("nope"), 5))
    }

    @Test fun `reciprocalRank is one over the rank of the first hit`() {
        assertEquals(0.5, reciprocalRank(listOf("a", "hit", "b"), expected))
        assertEquals(1.0, reciprocalRank(listOf("hit", "a", "b"), expected))
    }

    @Test fun `reciprocalRank is 0 when there is no hit`() {
        assertEquals(0.0, reciprocalRank(ranked, listOf("nope")))
    }

    @Test fun `ndcg ranks an early hit higher than a late hit`() {
        val early = ndcg(listOf("hit", "a", "b"), expected, 10)
        val late = ndcg(listOf("a", "b", "hit"), expected, 10)
        assertTrue(early > late, "hit at rank 1 must score higher than hit at rank 3")
    }

    @Test fun `ndcg of a top-ranked hit is 1`() {
        assertEquals(1.0, ndcg(listOf("hit", "a", "b"), expected, 10))
    }

    @Test fun `ndcg is 0 when there is no hit`() {
        assertEquals(0.0, ndcg(ranked, listOf("nope"), 10))
    }

    @Test fun `match is substring and case-insensitive`() {
        val ids = listOf("com.hypixel.hytale.container.ItemContainer#swapItems")
        assertEquals(1.0, recallAtK(ids, listOf("ItemContainer#swapItems"), 5))
        assertEquals(1.0, recallAtK(ids, listOf("itemcontainer#swapitems"), 5))
        assertEquals(1.0, recallAtK(listOf("ITEMCONTAINER#SWAPITEMS"), listOf("ItemContainer#swapItems"), 5))
    }

    @Test fun `loader parses the seed JSONL skipping blanks`() {
        val seed = """
            {"tool":"search_hytale_code","query":"swap items between containers","expectedIds":["ItemContainer#swapItems"]}
            {"tool":"search_hytale_code","query":"HytaleLogger create forEnclosingClass getLogger","expectedIds":["logger.HytaleLogger"]}

            {"tool":"search_hytale_code","query":"ParallelRangeTask ForkJoinPool parallelism","expectedIds":["task.ParallelRangeTask"]}
            {"tool":"search_hytale_code","query":"get player inventory","expectedIds":["getInventory"]}
            {"tool":"search_hytale_gamedata","query":"iron sword weapon damage","expectedIds":["Weapon_Sword_Iron"]}
            {"tool":"search_hytale_gamedata","query":"crafting recipe workbench","expectedIds":["Bench_"]}

            {"tool":"search_hytale_docs","query":"how to create a custom block","expectedIds":["creating-block"]}
        """.trimIndent()
        val file = Files.createTempFile("seed", ".jsonl")
        Files.writeString(file, seed)

        val queries = GoldenSet.load(file.toString())

        assertEquals(7, queries.size, "this inline fixture has exactly 7 non-blank lines")
        assertEquals("search_hytale_code", queries[0].tool)
        assertEquals("swap items between containers", queries[0].query)
        assertEquals(listOf("ItemContainer#swapItems"), queries[0].expectedIds)
        assertEquals("search_hytale_docs", queries[6].tool)
        assertEquals(listOf("creating-block"), queries[6].expectedIds)
        Files.deleteIfExists(file)
    }

    @Test fun `loader reads the checked-in seed resource`() {
        val seed = GoldenSet.loadSeed()
        assertTrue(seed.size >= 7, "seed grows over time; expected at least 7, got ${seed.size}")
        assertTrue(seed.any { it.expectedIds.contains("Weapon_Sword_Iron") })
    }
}
