package com.hyindex.knowledge.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnippetsTest {
    @Test fun underCapUnchanged() {
        assertEquals("hello", Snippets.truncate("hello", 100))
    }

    @Test fun overCapCutsAtNewlineWithEllipsis() {
        val text = "line1\nline2\nline3"

        assertEquals("line1\n…", Snippets.truncate(text, 9))
    }

    @Test fun noNewlineHardCut() {
        assertEquals("abcde\n…", Snippets.truncate("abcdefghij", 5))
    }

    @Test fun nonPositiveMaxReturnsAsIs() {
        assertEquals("abc", Snippets.truncate("abc", 0))
    }

    @Test fun windowCentersOnMatchDeepInBody() {
        val head = "a".repeat(200)
        val tail = "b".repeat(200)
        val text = "$head NEEDLE $tail"

        val result = Snippets.window(text, "needle", 100)

        assertTrue(result.contains("NEEDLE"))
        assertTrue(result.startsWith("…"))
    }

    @Test fun windowNoMatchEqualsTruncate() {
        val text = "line1\nline2\nline3\nline4\nline5"

        assertEquals(Snippets.truncate(text, 12), Snippets.window(text, "zzz", 12))
    }

    @Test fun windowShorterThanMaxUnchanged() {
        assertEquals("hello world", Snippets.window("hello world", "world", 100))
    }

    @Test fun windowNeverExceedsMaxPlusEllipses() {
        val text = "x".repeat(500) + " TARGET " + "y".repeat(500)
        val max = 80

        val result = Snippets.window(text, "target", max)

        assertTrue(result.length <= max + 2)
    }

    @Test fun windowMatchesCamelCaseViaSubWords() {
        val head = "z".repeat(200)
        val tail = "z".repeat(200)
        val text = "$head doSwapItems $tail"

        val result = Snippets.window(text, "swap items", 100)

        assertTrue(result.contains("doSwapItems"))
        assertTrue(result.startsWith("…"))
    }
}
