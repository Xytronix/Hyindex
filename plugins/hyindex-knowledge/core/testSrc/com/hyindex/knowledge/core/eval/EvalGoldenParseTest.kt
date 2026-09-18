// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvalGoldenParseTest {

    @Test
    fun `legacy expectedIds-only rows still parse`() {
        val queries = GoldenSet.parse(
            """{"tool":"search_hytale_code","query":"swap items between containers","expectedIds":["ItemContainer#swapItems"]}""",
        )
        assertEquals(1, queries.size)
        assertEquals("search_hytale_code", queries[0].tool)
        assertEquals("swap items between containers", queries[0].query)
        assertEquals(listOf("ItemContainer#swapItems"), queries[0].expectedIds)
        assertTrue(queries[0].judgments.isNullOrEmpty(), "legacy rows have no graded judgments")
        assertNull(queries[0].intent)
        assertTrue(queries[0].expectedCorpora.isNullOrEmpty())
    }

    @Test
    fun `optional graded judgments intent and expected corpora are retained`() {
        val queries = GoldenSet.parse(
            """{"tool":"search_hytale","query":"how to create a custom block","expectedIds":["creating-block"],"intent":"howto","expectedCorpora":["docs","code"],"judgments":[{"id":"creating-block","grade":3},{"id":"AbstractBlock","grade":1}]}""",
        )
        val q = queries.single()
        assertEquals("search_hytale", q.tool)
        assertEquals("howto", q.intent)
        assertEquals(listOf("docs", "code"), q.expectedCorpora)
        assertEquals(listOf("creating-block"), q.expectedIds)
        assertEquals(
            listOf(
                GradedJudgment("creating-block", 3),
                GradedJudgment("AbstractBlock", 1),
            ),
            q.judgments,
        )
    }

    @Test
    fun `checked-in seed resource is unchanged at 23 expectedIds-only rows`() {
        val seed = GoldenSet.loadSeed()
        assertEquals(23, seed.size)
        assertTrue(seed.all { it.expectedIds.isNotEmpty() })
        assertTrue(seed.none { !it.judgments.isNullOrEmpty() })
        assertTrue(seed.none { it.intent != null })
        assertTrue(seed.none { !it.expectedCorpora.isNullOrEmpty() })
        assertTrue(seed.any { it.expectedIds.contains("Weapon_Sword_Iron") })
        assertTrue(seed.any { it.query == "permission management for commands" })
    }
}
